# -*- coding: utf-8 -*-
"""
Compression des seuls poids en int8 (un facteur d'échelle par canal de sortie). Les calculs
restent en virgule flottante : le fichier est ~4 fois plus petit sans perdre la précision que
la quantification des activations fait perdre à ce modèle.
"""
import sys
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

src = sys.argv[1] if len(sys.argv) > 1 else "vision_model_op17.onnx"
dst = sys.argv[2] if len(sys.argv) > 2 else "vision_wq8.onnx"
MIN_SIZE = 1024  # les petits tenseurs (biais, normalisations) restent tels quels

model = onnx.load(src)
graph = model.graph
weights = {init.name: init for init in graph.initializer}
consumers = {}
for node in graph.node:
    for i, name in enumerate(node.input):
        consumers.setdefault(name, []).append((node, i))

replaced, saved = 0, 0
new_inits, new_nodes = [], []
for name, init in list(weights.items()):
    uses = consumers.get(name, [])
    # Uniquement les poids de Conv (entrée 1) et de MatMul/Gemm (entrée 1).
    if not uses or not all(n.op_type in ("Conv", "MatMul", "Gemm") and i == 1 for n, i in uses):
        continue
    w = numpy_helper.to_array(init)
    if w.dtype != np.float32 or w.size < MIN_SIZE or w.ndim < 2:
        continue
    # Conv : canal de sortie = axe 0. MatMul : poids [entrée, sortie] -> canal = axe 1.
    axis = 0 if uses[0][0].op_type in ("Conv", "Gemm") else w.ndim - 1
    reduce_axes = tuple(a for a in range(w.ndim) if a != axis)
    scale = np.abs(w).max(axis=reduce_axes) / 127.0
    scale[scale == 0] = 1.0
    shape = [1] * w.ndim; shape[axis] = -1
    q = np.clip(np.round(w / scale.reshape(shape)), -127, 127).astype(np.int8)

    qname, sname, zname = name + "_q", name + "_scale", name + "_zp"
    new_inits += [numpy_helper.from_array(q, qname),
                  numpy_helper.from_array(scale.astype(np.float32), sname),
                  numpy_helper.from_array(np.zeros_like(scale, dtype=np.int8), zname)]
    new_nodes.append(helper.make_node("DequantizeLinear", [qname, sname, zname], [name],
                                      name=name + "_dq", axis=axis))
    graph.initializer.remove(init)
    replaced += 1
    saved += w.nbytes - q.nbytes

graph.initializer.extend(new_inits)
# Les nœuds de déquantification doivent précéder leurs utilisateurs.
nodes = new_nodes + list(graph.node)
del graph.node[:]
graph.node.extend(nodes)
onnx.checker.check_model(model)
onnx.save(model, dst)
print("%d tenseurs compresses, %.1f Mo economises -> %s" % (replaced, saved / 1048576, dst))
