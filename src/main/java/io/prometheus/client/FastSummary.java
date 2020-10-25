package io.prometheus.client;

import io.prometheus.client.CKMSQuantiles.Quantile;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FastSummary extends SimpleCollector<FastSummary.Child> implements Collector.Describable {

  final List<Quantile> quantiles;
  final long maxAgeSeconds;
  final int ageBuckets;

  private FastSummary(Builder b) {
    super(b);
    quantiles = List.copyOf(b.quantiles);
    this.maxAgeSeconds = b.maxAgeSeconds;
    this.ageBuckets = b.ageBuckets;
    initializeNoLabelsChild();
  }


  @Override
  public List<MetricFamilySamples> describe() {
    return Collections.singletonList(new SummaryMetricFamily(fullname, help, labelNames));
  }

  @Override
  protected Child newChild() {
    return new Child(quantiles, maxAgeSeconds, ageBuckets);
  }

  @Override
  public List<MetricFamilySamples> collect() {
    final List<Sample> samples = new ArrayList<>();
    children.forEach((ck, cv) -> {
      final var cvv = cv.get();
      final var labelNamesWithQuantile = new ArrayList<>(labelNames);
      labelNamesWithQuantile.add("quantile");
      cvv.quantiles.forEach((k, v) -> {
        final var labelValuesWithQuantile = new ArrayList<String>(ck.size() + 1);
        labelNamesWithQuantile.addAll(ck);
        labelValuesWithQuantile.add(doubleToGoString(k));
        samples.add(new Sample(fullname, labelNamesWithQuantile, labelValuesWithQuantile, v));
      });
      samples.add(new Sample(fullname + "_count", labelNames, ck, cvv.count));
      samples.add(new Sample(fullname + "_sum", labelNames, ck, cvv.sum));
    });
    return familySamplesList(Type.SUMMARY, samples);
  }

  public static class Builder extends SimpleCollector.Builder<Builder, FastSummary> {

    private final List<Quantile> quantiles = new ArrayList<>();
    private long maxAgeSeconds = TimeUnit.MINUTES.toSeconds(10);
    private int ageBuckets = 5;

    public Builder quantile(double quantile, double error) {
      if (quantile < 0.0 || quantile > 1.0) {
        throw new IllegalArgumentException("Quantile " + quantile + " invalid: Expected number between 0.0 and 1.0.");
      }
      if (error < 0.0 || error > 1.0) {
        throw new IllegalArgumentException("Error " + error + " invalid: Expected number between 0.0 and 1.0.");
      }
      quantiles.add(new Quantile(quantile, error));
      return this;
    }

    public Builder maxAgeSeconds(long maxAgeSeconds) {
      if (maxAgeSeconds <= 0) {
        throw new IllegalArgumentException("maxAgeSeconds cannot be " + maxAgeSeconds);
      }
      this.maxAgeSeconds = maxAgeSeconds;
      return this;
    }

    public Builder ageBuckets(int ageBuckets) {
      if (ageBuckets <= 0) {
        throw new IllegalArgumentException("ageBuckets cannot be " + ageBuckets);
      }
      this.ageBuckets = ageBuckets;
      return this;
    }

    @Override
    public FastSummary create() {
      for (String label : labelNames) {
        if (label.equals("quantile")) {
          throw new IllegalStateException("Summary cannot have a label named 'quantile'.");
        }
      }
      dontInitializeNoLabelsChild = true;
      return new FastSummary(this);
    }
  }

  public static class Child {

    double count;
    double sum;
    final List<Quantile> quantiles;
    final TimeWindowCMKSQuantiles quantileValues;

    private Child(List<Quantile> quantiles, long maxAgeSeconds, int ageBuckets) {
      this.quantiles = quantiles;
      if (quantiles.size() > 0) {
        quantileValues = new TimeWindowCMKSQuantiles(quantiles.toArray(Quantile[]::new), maxAgeSeconds, ageBuckets, System.currentTimeMillis());
      } else {
        quantileValues = null;
      }
    }

    public void observe(double amount) {
      BackgroundThread.enqueue(new BackgroundThread.PutSummaryOp(System.currentTimeMillis(), amount, this));
    }

    public Value get() {
      return BackgroundThread.get(new BackgroundThread.GetSummaryOp(System.currentTimeMillis(), this)).value;
    }

    public static class Value {

      public final long now;
      public final double count;
      public final double sum;
      public final SortedMap<Double, Double> quantiles;

      Value(long now, double count, double sum, List<Quantile> quantiles, TimeWindowCMKSQuantiles quantileValues) {
        this.now = now;
        this.count = count;
        this.sum = sum;
        this.quantiles = Collections.unmodifiableSortedMap(snapshot(quantiles, quantileValues));
      }

      private SortedMap<Double, Double> snapshot(List<Quantile> quantiles, TimeWindowCMKSQuantiles quantileValues) {
        return quantiles.stream().collect(
          Collectors.toMap(
            q -> q.quantile,
            q -> quantileValues.get(now, q.quantile),
            (v1, v2) -> v2,
            TreeMap::new
          )
        );
      }
    }
  }
}
