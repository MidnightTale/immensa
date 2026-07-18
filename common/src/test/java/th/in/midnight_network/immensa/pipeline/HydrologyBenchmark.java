package th.in.midnight_network.immensa.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight repeatable benchmark for the default 256 + 2*128 hydrology tile. */
public final class HydrologyBenchmark {
    private HydrologyBenchmark() {}

    public static void main(String[] args) throws Exception {
        int size = 512;
        LocalTerrainProvider.HeightmapData input = HydrologyProcessorTest.syntheticTerrain(size, size);
        for (int i = 0; i < 3; i++) run(input);

        int iterations = 10;
        long sequentialStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) run(input);
        double sequentialSeconds = (System.nanoTime() - sequentialStart) / 1_000_000_000.0;

        int threads = Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors() / 2));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        int parallelIterations = threads * 2;
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < parallelIterations; i++) jobs.add(() -> { run(input); return null; });
        long parallelStart = System.nanoTime();
        executor.invokeAll(jobs).forEach(future -> {
            try { future.get(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        double parallelSeconds = (System.nanoTime() - parallelStart) / 1_000_000_000.0;
        executor.shutdownNow();

        System.out.printf("Hydrology %dx%d sequential: %.2f ms/tile (%.2f tiles/s)%n",
                size, size, sequentialSeconds * 1000 / iterations, iterations / sequentialSeconds);
        System.out.printf("Hydrology %dx%d parallel (%d threads): %.2f tiles/s%n",
                size, size, threads, parallelIterations / parallelSeconds);
    }

    private static void run(LocalTerrainProvider.HeightmapData input) {
        HydrologyProcessor.process(input, 128, 30, 1.5f, 0.12f, 300);
    }
}
