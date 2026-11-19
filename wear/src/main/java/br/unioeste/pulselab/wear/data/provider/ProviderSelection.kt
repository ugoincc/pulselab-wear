package br.unioeste.pulselab.wear.data.provider

import android.content.Context
import android.util.Log
import br.unioeste.pulselab.wear.data.health.SAMSUNG_PROVIDER_ID

private const val TAG = "ProviderSelection"
private const val PREFS = "provider_selection"
private const val KEY_PROVIDER = "provider_id"
private const val KEY_REPLAY_SEED = "replay_seed"
private const val KEY_REPLAY_INTERVAL_MS = "replay_interval_ms"
private const val KEY_REPLAY_BATCH_SIZE = "replay_batch_size"

/**
 * The sources `SensorStreamService` can be pointed at. The enum, not a bare
 * string, is what makes an unknown value a decision rather than a crash — see
 * [ProviderChoice.of].
 */
enum class ProviderChoice(val id: String) {
    /** Samsung Health Sensor SDK — every channel the watch can read. */
    SAMSUNG(SAMSUNG_PROVIDER_ID),

    /** Jetpack Health Services — heart rate only, plus motion read separately. */
    HEALTH_SERVICES(HEALTH_SERVICES_PROVIDER_ID),

    /** Deterministic replay — the instrument of the evaluation, not a sensor. */
    REPLAY(REPLAY_PROVIDER_ID);

    companion object {
        fun of(id: String?): ProviderChoice =
            entries.firstOrNull { it.id == id } ?: SAMSUNG
    }
}

/**
 * Which [SensorProvider] the service collects from, and the replay parameters
 * that are factors of the experiment (cap6-avaliacao.tex §6.5).
 *
 * Persisted rather than passed as an Intent extra because `SensorStreamService`
 * returns `START_STICKY`: the system restarts it with a **null** Intent, so a
 * choice that lived only in the extra would silently revert to the default
 * mid-run — and an evaluation run that changes provider halfway without saying
 * so produces data that looks valid and is not.
 */
object ProviderSelection {

    fun selected(context: Context): ProviderChoice =
        ProviderChoice.of(prefs(context).getString(KEY_PROVIDER, null))

    fun select(context: Context, choice: ProviderChoice) {
        prefs(context).edit().putString(KEY_PROVIDER, choice.id).apply()
        Log.i(TAG, "Provider selected: ${choice.id}")
    }

    /**
     * Replay parameters. Settable so the evaluation harness can sweep cadence
     * and batch size without a rebuild; defaulted to the real sensor's
     * behaviour so an accidental replay run is at least comparable.
     */
    fun configureReplay(
        context: Context,
        seed: Long = ReplayProvider.DEFAULT_SEED,
        emitIntervalMs: Long = REPLAY_DEFAULT_INTERVAL_MS,
        batchSize: Int = 1
    ) {
        prefs(context).edit()
            .putLong(KEY_REPLAY_SEED, seed)
            .putLong(KEY_REPLAY_INTERVAL_MS, emitIntervalMs)
            .putInt(KEY_REPLAY_BATCH_SIZE, batchSize)
            .apply()
        Log.i(TAG, "Replay configured: seed=$seed interval=${emitIntervalMs}ms batch=$batchSize")
    }

    /**
     * Builds the currently selected provider. A fresh instance per call: the
     * providers hold their connection state inside the flow's lifetime, and
     * `sequenceNumber` restarts with the collection (ESPECIFICACAO §5), so
     * reusing an instance across service restarts would gain nothing and blur
     * the connection boundary the phone relies on.
     */
    fun create(context: Context): SensorProvider {
        val appContext = context.applicationContext
        return when (selected(appContext)) {
            ProviderChoice.SAMSUNG -> SamsungSensorProvider(appContext)
            ProviderChoice.HEALTH_SERVICES -> HealthServicesProvider(appContext)
            ProviderChoice.REPLAY -> {
                val p = prefs(appContext)
                ReplayProvider.forEvaluation(
                    context = appContext,
                    seed = p.getLong(KEY_REPLAY_SEED, ReplayProvider.DEFAULT_SEED),
                    emitIntervalMs = p.getLong(KEY_REPLAY_INTERVAL_MS, REPLAY_DEFAULT_INTERVAL_MS),
                    batchSize = p.getInt(KEY_REPLAY_BATCH_SIZE, 1)
                )
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
