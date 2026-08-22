package com.corepulse.task.executor;

import java.util.concurrent.TimeUnit;

/**
 * 独立 JVM 内存压力测试进程。
 * 由 MemStressTaskExecutor 启动和销毁，不直接参与 Spring 容器生命周期。
 */
public final class MemoryStressWorker {

    private static final int BLOCK_SIZE_MB = 64;

    private MemoryStressWorker() {
    }

    public static void main(String[] args) throws InterruptedException {
        long targetMb = args.length > 0 ? Long.parseLong(args[0]) : BLOCK_SIZE_MB;
        int blockCount = Math.toIntExact((targetMb + BLOCK_SIZE_MB - 1) / BLOCK_SIZE_MB);
        byte[][] blocks = new byte[blockCount][];
        int allocatedBlocks = 0;

        for (int i = 0; i < blockCount; i++) {
            int blockSizeMb = (int) Math.min(BLOCK_SIZE_MB,
                    targetMb - (long) i * BLOCK_SIZE_MB);
            try {
                blocks[i] = new byte[blockSizeMb * 1024 * 1024];
                allocatedBlocks++;
            } catch (OutOfMemoryError error) {
                System.err.println("Memory allocation reached system limit: allocated="
                        + ((long) allocatedBlocks * BLOCK_SIZE_MB) + " MB, requested=" + targetMb + " MB");
                break;
            }
        }

        if (allocatedBlocks == 0) {
            System.err.println("Unable to allocate memory for stress test");
            System.exit(2);
            return;
        }

        System.out.println("Memory stress is running: allocated="
                + ((long) allocatedBlocks * BLOCK_SIZE_MB) + " MB");
        int offset = 0;
        while (true) {
            for (int i = 0; i < allocatedBlocks; i++) {
                byte[] block = blocks[i];
                block[offset++ & (block.length - 1)]++;
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
    }
}
