package br.unioeste.pulselab.wear.model

object MessagePaths {
    /**
     * One message per sensor burst, carrying a [SensorBatch].
     *
     * A new path rather than a new shape on the old `/sensor/data`: a phone build
     * that predates batching listens only to the old path, so it now receives
     * nothing instead of parsing an array as if it were one sample. Failing
     * silent beats failing wrong.
     */
    const val SENSOR_BATCH = "/sensor/batch"
}
