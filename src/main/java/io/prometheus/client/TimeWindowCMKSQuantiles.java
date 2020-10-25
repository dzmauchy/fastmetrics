package io.prometheus.client;

import io.prometheus.client.CKMSQuantiles.Quantile;

import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

class TimeWindowCMKSQuantiles {

  private final Quantile[] quantiles;
  private final CKMSQuantiles[] ringBuffer;
  private int currentBucket;
  private long lastRotateTimestampMillis;
  private final long durationBetweenRotatesMillis;

  public TimeWindowCMKSQuantiles(Quantile[] quantiles, long maxAgeSeconds, int ageBuckets, long now) {
    this.quantiles = quantiles;
    this.ringBuffer = IntStream.range(0, ageBuckets).mapToObj(i -> new CKMSQuantiles(quantiles)).toArray(CKMSQuantiles[]::new);
    this.currentBucket = 0;
    this.lastRotateTimestampMillis = now;
    this.durationBetweenRotatesMillis = TimeUnit.SECONDS.toMillis(maxAgeSeconds) / ageBuckets;
  }

  public double get(long now, double q) {
    CKMSQuantiles currentBucket = rotate(now);
    return currentBucket.get(q);
  }

  public void insert(long now, double value) {
    rotate(now);
    for (CKMSQuantiles ckmsQuantiles : ringBuffer) {
      ckmsQuantiles.insert(value);
    }
  }

  private CKMSQuantiles rotate(long now) {
    long timeSinceLastRotateMillis = now - lastRotateTimestampMillis;
    while (timeSinceLastRotateMillis > durationBetweenRotatesMillis) {
      ringBuffer[currentBucket] = new CKMSQuantiles(quantiles);
      if (++currentBucket >= ringBuffer.length) {
        currentBucket = 0;
      }
      timeSinceLastRotateMillis -= durationBetweenRotatesMillis;
      lastRotateTimestampMillis += durationBetweenRotatesMillis;
    }
    return ringBuffer[currentBucket];
  }
}
