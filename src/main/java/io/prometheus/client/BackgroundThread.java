package io.prometheus.client;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map;
import java.util.concurrent.LinkedTransferQueue;

final class BackgroundThread extends Thread {

  public BackgroundThread() {
    super(null, null, "FastMetrics", 128L << 20, false);
    setDaemon(true);
  }

  private static final LinkedTransferQueue<Object> COMMANDS = new LinkedTransferQueue<>();
  private static final LinkedTransferQueue<Map.Entry<OpWithResult<?>, Result>> RESULTS = new LinkedTransferQueue<>();
  private static final BackgroundThread THREAD = new BackgroundThread();

  static {
    THREAD.start();
  }

  @Override
  public void run() {
    while (!isInterrupted()) {
      final var op = COMMANDS.poll();
      if (op == null) {
        Thread.onSpinWait();
      } else {
        final var r = result(op);
        if (op instanceof OpWithResult) {
          RESULTS.offer(new SimpleImmutableEntry<>((OpWithResult<?>) op, r));
        }
      }
    }
  }

  private Result result(Object op) {
    if (op instanceof PutSummaryOp) {
      final var o = (PutSummaryOp) op;
      o.summary.sum += o.amount;
      o.summary.count += 1.0;
      if (o.summary.quantileValues != null) {
        o.summary.quantileValues.insert(o.now, o.amount);
      }
    } else if (op instanceof GetSummaryOp) {
      final var o = (GetSummaryOp) op;
      return new GetSummaryResult(
        new FastSummary.Child.Value(o.now, o.summary.count, o.summary.sum, o.summary.quantiles, o.summary.quantileValues)
      );
    }
    return null;
  }

  static void enqueue(PutSummaryOp op) {
    COMMANDS.offer(op);
  }

  @SuppressWarnings("unchecked")
  static <R extends Result> R get(OpWithResult<R> op) {
    COMMANDS.offer(op);
    while (true) {
      final var r = RESULTS.peek();
      if (r == null) {
        Thread.onSpinWait();
      } else {
        if (r.getKey() == op) {
          final var ar = RESULTS.poll();
          if (ar != r) {
            throw new Error("Queue ordering error");
          }
          return (R) r.getValue();
        } else {
          Thread.onSpinWait();
        }
      }
    }
  }

  static class OpWithResult<R extends Result> {
  }

  static final class PutSummaryOp {

    private final long now;
    private final double amount;
    private final FastSummary.Child summary;

    PutSummaryOp(long now, double amount, FastSummary.Child summary) {
      this.now = now;
      this.amount = amount;
      this.summary = summary;
    }
  }

  static final class GetSummaryOp extends OpWithResult<GetSummaryResult> {

    private final long now;
    private final FastSummary.Child summary;

    GetSummaryOp(long now, FastSummary.Child summary) {
      this.now = now;
      this.summary = summary;
    }
  }

  static class Result {
  }

  static final class GetSummaryResult extends Result {

    public final FastSummary.Child.Value value;

    GetSummaryResult(FastSummary.Child.Value value) {
      this.value = value;
    }
  }
}
