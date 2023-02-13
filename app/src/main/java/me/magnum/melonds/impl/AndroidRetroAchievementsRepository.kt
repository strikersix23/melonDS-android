package me.magnum.melonds.impl

import android.content.SharedPreferences
import androidx.core.content.edit
import me.magnum.melonds.database.daos.RAAchievementsDao
import me.magnum.melonds.database.entities.retroachievements.RAGameHashEntity
import me.magnum.melonds.database.entities.retroachievements.RAGameSetMetadata
import me.magnum.melonds.database.entities.retroachievements.RAPendingAchievementSubmissionEntity
import me.magnum.melonds.database.entities.retroachievements.RAUserAchievementEntity
import me.magnum.melonds.domain.model.retroachievements.RAUserAchievement
import me.magnum.melonds.domain.repositories.RetroAchievementsRepository
import me.magnum.melonds.impl.mappers.retroachievements.mapToEntity
import me.magnum.melonds.impl.mappers.retroachievements.mapToModel
import me.magnum.rcheevosapi.RAApi
import me.magnum.rcheevosapi.RAUserAuthStore
import me.magnum.rcheevosapi.model.RAAchievement
import me.magnum.rcheevosapi.model.RAGameId
import java.time.Duration
import java.time.Instant

class AndroidRetroAchievementsRepository(
    private val raApi: RAApi,
    private val achievementsDao: RAAchievementsDao,
    private val raUserAuthStore: RAUserAuthStore,
    private val sharedPreferences: SharedPreferences,
) : RetroAchievementsRepository {

    private companion object {
        const val RA_HASH_LIBRARY_LAST_UPDATED = "ra_hash_library_last_updated"
    }

    override suspend fun isUserAuthenticated(): Boolean {
        return raUserAuthStore.getUserAuth() != null
    }

    override suspend fun login(username: String, password: String): Result<Unit> {
        return raApi.login(username, password)
    }

    override suspend fun getGameUserAchievements(gameHash: String): Result<List<RAUserAchievement>> {
        val gameIdResult = getGameIdFromGameHash(gameHash)
        if (gameIdResult.isFailure) {
            return Result.failure(gameIdResult.exceptionOrNull()!!)
        }

        val gameId = gameIdResult.getOrThrow()
        if (gameId == null) {
            return Result.success(emptyList())
        }

        val gameSetMetadata = achievementsDao.getGameSetMetadata(gameId.id)
        val currentMetadata = CurrentGameSetMetadata(gameId, gameSetMetadata)

        val gameAchievementsResult = fetchGameAchievements(gameId, currentMetadata)
        if (gameAchievementsResult.isFailure) {
            return Result.failure(gameAchievementsResult.exceptionOrNull()!!)
        }

        val userUnlocksResult = fetchGameUserUnlockedAchievements(gameId, currentMetadata)
        if (userUnlocksResult.isFailure) {
            return Result.failure(userUnlocksResult.exceptionOrNull()!!)
        }

        val gameAchievements = gameAchievementsResult.getOrThrow()
        val userUnlocks = userUnlocksResult.getOrThrow()

        val userAchievements = gameAchievements.map {
            RAUserAchievement(
                achievement = it,
                isUnlocked = userUnlocks.contains(it.id),
            )
        }
        return Result.success(userAchievements)
    }

    override suspend fun getAchievement(achievementId: Long): Result<RAAchievement?> {
        return runCatching {
            achievementsDao.getAchievement(achievementId)
        }.map {
            it?.mapToModel()
        }
    }

    override suspend fun awardAchievement(achievement: RAAchievement) {
        runCatching {
            raApi.awardAchievement(achievement, false)
        }.onSuccess {
            val userAchievement = RAUserAchievementEntity(achievement.gameId.id, achievement.id, true)
            achievementsDao.addUserAchievement(userAchievement)
        }.onFailure {
            val pendingAchievementSubmissionEntity = RAPendingAchievementSubmissionEntity(
                achievementId = achievement.id,
                forHardcoreMode = false,
            )
            achievementsDao.addPendingAchievementSubmission(pendingAchievementSubmissionEntity)
        }
    }

    private suspend fun getGameIdFromGameHash(gameHash: String): Result<RAGameId?> {
        return if (mustRefreshHashLibrary()) {
            raApi.getGameHashList()
                .onSuccess { gameHashes ->
                    val gameHashEntities = gameHashes.map {
                        RAGameHashEntity(it.key, it.value.id)
                    }
                    achievementsDao.updateGameHashLibrary(gameHashEntities)
                    sharedPreferences.edit {
                        putLong(RA_HASH_LIBRARY_LAST_UPDATED, Instant.now().toEpochMilli())
                    }
                }
                .map {
                    it[gameHash]
                }
        } else {
            runCatching {
                achievementsDao.getGameHashEntity(gameHash)
            }.map {
                it?.let {
                    RAGameId(it.gameId)
                }
            }
        }
    }

    private suspend fun fetchGameAchievements(gameId: RAGameId, gameSetMetadata: CurrentGameSetMetadata): Result<List<RAAchievement>> {
        return if (mustRefreshAchievementSet(gameSetMetadata.currentMetadata)) {
            raApi.getGameInfo(gameId).map { game ->
                game.achievements
            }.onSuccess { achievements ->
                val achievementEntities = achievements.map {
                    it.mapToEntity()
                }

                val newMetadata = gameSetMetadata.withNewAchievementSetUpdate()
                achievementsDao.updateGameAchievements(achievementEntities)
                achievementsDao.updateGameSetMetadata(newMetadata)
            }
        } else {
            runCatching {
                achievementsDao.getGameAchievements(gameId.id).map {
                    it.mapToModel()
                }
            }
        }.map { achievements ->
            achievements.filter { it.type == RAAchievement.Type.CORE }
        }
    }

    private suspend fun fetchGameUserUnlockedAchievements(gameId: RAGameId, gameSetMetadata: CurrentGameSetMetadata): Result<List<Long>> {
        return if (mustRefreshUserData(gameSetMetadata.currentMetadata)) {
            raApi.getUserUnlockedAchievements(gameId, false).onSuccess { userUnlocks ->
                val userAchievementEntities = userUnlocks.map {
                    RAUserAchievementEntity(
                        gameId.id,
                        it,
                        true,
                    )
                }

                val newMetadata = gameSetMetadata.withNewUserAchievementsUpdate()
                achievementsDao.updateGameUserUnlockedAchievements(gameId.id, userAchievementEntities)
                achievementsDao.updateGameSetMetadata(newMetadata)
            }
        } else {
            runCatching {
                achievementsDao.getGameUserUnlockedAchievements(gameId.id).map {
                    it.achievementId
                }
            }
        }
    }

    private fun mustRefreshHashLibrary(): Boolean {
        val hashLibraryLastUpdateTimestamp = sharedPreferences.getLong(RA_HASH_LIBRARY_LAST_UPDATED, 0)
        val hashLibraryLastUpdate = Instant.ofEpochMilli(hashLibraryLastUpdateTimestamp)

        // Update the game hash library once a month
        return Duration.between(hashLibraryLastUpdate, Instant.now()) > Duration.ofDays(30)
    }

    private fun mustRefreshAchievementSet(gameSetMetadata: RAGameSetMetadata?): Boolean {
        if (gameSetMetadata?.lastAchievementSetUpdated == null) {
            return true
        }

        // Update the achievement set once a week
        return Duration.between(gameSetMetadata.lastAchievementSetUpdated, Instant.now()) >= Duration.ofDays(7)
    }

    private fun mustRefreshUserData(gameSetMetadata: RAGameSetMetadata?): Boolean {
        if (gameSetMetadata?.lastUserDataUpdated == null) {
            return true
        }

        // Sync user achievement data once a day
        return Duration.between(gameSetMetadata.lastUserDataUpdated, Instant.now()) >= Duration.ofDays(1)
    }

    private class CurrentGameSetMetadata(private val gameId: RAGameId, initialMetadata: RAGameSetMetadata?) {
        var currentMetadata = initialMetadata
            private set

        fun withNewAchievementSetUpdate(): RAGameSetMetadata {
            return (currentMetadata?.copy(lastAchievementSetUpdated = Instant.now()) ?: RAGameSetMetadata(gameId.id, Instant.now(), null)).also {
                currentMetadata = it
            }
        }

        fun withNewUserAchievementsUpdate(): RAGameSetMetadata {
            return (currentMetadata?.copy(lastUserDataUpdated = Instant.now()) ?: RAGameSetMetadata(gameId.id, null, Instant.now())).also {
                currentMetadata = it
            }
        }
    }
}