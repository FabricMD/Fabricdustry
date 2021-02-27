/*
 * Copyright 2021 kb1000
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.kb1000.fabricmd.hackloader;

import arc.Core;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.mod.Mod;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Objects;

import static arc.util.Reflect.set;

public class GameReplacer {
    // TODO: un-hardcode this list
    private static final String[] JARS = new String[] {
            "access-widener-1.0.0.jar", "asm-9.0.jar", "asm-analysis-9.0.jar", "asm-commons-9.0.jar",
            "asm-tree-9.0.jar", "asm-util-9.0.jar", "fabric-loader-0.11.1.1+local.jar",
            "fabric-loader-sat4j-2.3.5.4.jar", "gson-2.8.6.jar", "guava-28.2-jre.jar", "jimfs-1.2-fabric.jar",
            "log4j-api-2.11.2.jar", "log4j-core-2.11.2.jar", "sponge-mixin-0.8.2+build.24.jar"
    };

    public static void replace() throws IOException, InterruptedException, ReflectiveOperationException, URISyntaxException {
        boolean isServer = Vars.headless;
        final long window;
        final long context;
        if (!isServer) {
            window = Reflect.get(Core.app, "window");
            context = Reflect.get(Core.app, "context");
        } else {
            window = context = 0;
        }

        URL fabricdustryLocation = GameReplacer.class.getProtectionDomain().getCodeSource().getLocation();
        URL fabricdustryJarsLocation;
        if (!fabricdustryLocation.getProtocol().equals("file") || !Files.isDirectory(Paths.get(fabricdustryLocation.toURI()))) {
            final Path path = Files.createTempDirectory("fabricdustrytmp");
            path.toFile().deleteOnExit();
            for (final String jar : JARS) {
                try (final InputStream is = GameReplacer.class.getClassLoader().getResourceAsStream(jar)) {
                    final Path jarFile = path.resolve(jar);
                    // This relies on the deletion order, which is specified to be the reverse of the registration order
                    jarFile.toFile().deleteOnExit();
                    Files.copy(Objects.requireNonNull(is), jarFile);
                }
            }

            fabricdustryJarsLocation = path.toUri().toURL();
        } else {
            fabricdustryJarsLocation = fabricdustryLocation;
        }
        final URL mindustryJar = Mod.class.getProtectionDomain().getCodeSource().getLocation();
        String fabricdustryLocationURL = fabricdustryJarsLocation.toExternalForm();
        if (!fabricdustryLocationURL.endsWith("/"))
            fabricdustryLocationURL += '/';
        final ArrayList<URL> urls = new ArrayList<>();
        for (final String jar : JARS) {
            urls.add(new URL(fabricdustryLocationURL + jar));
        }
        urls.add(mindustryJar);
        final URLClassLoader ucl = new URLClassLoader(urls.toArray(new URL[0]), Mod.class.getClassLoader().getParent());
        final URLClassLoader childUcl = new URLClassLoader(new URL[] {fabricdustryLocation}, ucl);

        boolean isInMindustryJava;
        try {
            isInMindustryJava = JavaDetection.isMindustryJava();
        } catch (LinkageError e) {
            isInMindustryJava = true;
        }

        if (isInMindustryJava) {
            System.setProperty("log4j2.disable.jmx", "true");
        }

        @SuppressWarnings("unchecked") final Class<? extends Runnable> gameLoaderClass = (Class<? extends Runnable>) childUcl.loadClass("de.kb1000.fabricmd.hackloader.post.NewGameLoader");
        final Runnable gameLoader = gameLoaderClass.getConstructor().newInstance();
        set(gameLoader, "isServer", isServer);
        if (!isServer) {
            set(gameLoader, "window", window);
            set(gameLoader, "context", context);
        }
        set(gameLoader, "isInMindustryJava", isInMindustryJava);
        set(gameLoader, "oldClassLoader", Mod.class.getClassLoader());
        final Thread thread = new Thread(gameLoader);
        thread.setDaemon(false);
        thread.setContextClassLoader(ucl);
        thread.start();


        // Block the current thread forever
        Object lock = new Object();
        // Yes, it's intended. Stop asking.
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (lock) {
            lock.wait();
        }
    }
}
