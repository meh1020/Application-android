package com.vista.photoeditor.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Analyse de toute la galerie (jusqu'à [SearchIndex.MAX_BACKGROUND_PHOTOS]) en tâche de fond,
 * quand le téléphone est en charge : les catégories, la recherche et les doublons couvrent alors
 * aussi les photos anciennes, sans que l'app soit ouverte. Interrompue dès que le téléphone est
 * débranché ; le travail fait est gardé et reprend à la recharge suivante.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MediaPermissions.hasAccess(applicationContext)) return Result.success()
        return try {
            val photos = withContext(Dispatchers.IO) { MediaRepository.loadPhotos(applicationContext) }
            SearchIndex.get(applicationContext).ensureIndexed(photos, SearchIndex.MAX_BACKGROUND_PHOTOS)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "vista-index"

        /** Programme l'analyse une fois par jour, en charge ; sans effet si elle l'est déjà. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
