package com.bank.payments.incident;

import java.lang.management.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public class RedeployDemo {
    public static void main(String[] args) throws Exception {
        URL[] path = { Path.of(args[0]).toUri().toURL() };
        int deployments = Integer.parseInt(args[1]);
        boolean stopModule = Boolean.parseBoolean(args[2]);
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        MemoryPoolMXBean metaspace = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getName().equals("Metaspace")).findFirst().orElseThrow();
        for (int d = 1; d <= deployments; d++) {
            URLClassLoader loader = new URLClassLoader("fee-grid-v" + d, path, RedeployDemo.class.getClassLoader());
            BankModule module = (BankModule) loader.loadClass("com.bank.feegrid.FeeGridModule")
                    .getDeclaredConstructor().newInstance();
            module.start();
            if (stopModule) module.stop();
            loader.close();
            module = null; loader = null;
            System.gc();
            if (d % 10 == 0 || d == 1) {
                System.out.printf("deploy %2d: loaded %,6d  unloaded %,6d  Metaspace %3d MB  listeners %d%n",
                        d, classes.getLoadedClassCount(), classes.getUnloadedClassCount(),
                        metaspace.getUsage().getUsed() >> 20, RateChangeRegistry.size());
            }
        }
        if (args.length > 3) Thread.sleep(Long.MAX_VALUE);
    }
}
