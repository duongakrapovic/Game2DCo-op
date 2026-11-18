package source.server;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Verifies lazy class loading after another launcher compiles, without loading assets or saves. */
public final class LauncherRegressionChecks {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected server and client class directories");
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader server = new URLClassLoader(new URL[] {Path.of(args[0]).toUri().toURL()}, platform);
                URLClassLoader client = new URLClassLoader(new URL[] {Path.of(args[1]).toUri().toURL()}, platform)) {
            Class.forName("source.server.ServerApp", false, server).getDeclaredMethods();
            for (String launcher : new String[] {"run-client.bat", "run-server.bat"}) {
                Process compilation = new ProcessBuilder("cmd.exe", "/d", "/c", launcher + " --compile-only")
                        .inheritIO().start();
                if (compilation.waitFor() != 0) throw new AssertionError("Compilation failed: " + launcher);
            }
            for (String name : new String[] {"source.server.ServerGame", "source.server.RoomSaveManager",
                    "source.common.SaveManager", "source.common.CombatSystem", "source.common.MonsterAI"}) {
                Class.forName(name, false, server).getDeclaredConstructors();
            }
            for (String name : new String[] {"source.client.ClientApp", "source.client.ClientGame",
                    "source.client.ClientRenderer", "source.client.OfflineGameLoop", "source.client.ScoreboardOverlay"}) {
                Class.forName(name, false, client).getDeclaredConstructors();
            }
            try {
                Class.forName("source.server.ServerApp", false, client);
                throw new AssertionError("Client output unexpectedly contains server classes");
            } catch (ClassNotFoundException expected) {
                // Production client compilation must exclude tests importing server code.
            }
            System.out.println("PASS: server lazy loading survives other launches; client/server classpaths are isolated");
        }
    }
}
