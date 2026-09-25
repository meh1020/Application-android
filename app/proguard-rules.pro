# ONNX Runtime : sa bibliothèque native crée et appelle ces classes par leur nom (JNI).
# Sans cette règle, l'optimiseur les supprime ou les renomme et l'analyse des photos plante.
-keep class ai.onnxruntime.** { *; }
