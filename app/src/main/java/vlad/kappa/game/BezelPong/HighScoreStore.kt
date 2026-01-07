package vlad.kappa.game.BezelPong

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bezel_pong")

object HighScoreStore {
    private val KEY_HIGH = intPreferencesKey("high_score")

    fun flow(context: Context): Flow<Int> =
        context.dataStore.data.map { prefs -> prefs[KEY_HIGH] ?: 0 }

    suspend fun setIfGreater(context: Context, score: Int) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_HIGH] ?: 0
            if (score > current) prefs[KEY_HIGH] = score
        }
    }

    suspend fun reset(context: Context) {
        context.dataStore.edit { prefs -> prefs[KEY_HIGH] = 0 }
    }
}
