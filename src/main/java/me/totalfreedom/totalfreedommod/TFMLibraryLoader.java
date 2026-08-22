package me.totalfreedom.totalfreedommod;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves TFM's heavyweight runtime libraries from Maven Central at plugin
 * load time instead of shading them into the jar.
 */
public class TFMLibraryLoader implements PluginLoader
{

    private static final String[] LIBRARIES = {
            "com.discord4j:discord4j-core:3.3.2",
            "org.apache.sshd:sshd-core:2.17.1",
            "net.i2p.crypto:eddsa:0.3.0",
            "org.postgresql:postgresql:42.7.7",
            "org.xerial:sqlite-jdbc:3.49.1.0",
            "com.mysql:mysql-connector-j:9.3.0",
            "io.projectreactor:reactor-core:3.8.3",
            "com.zaxxer:HikariCP:6.3.0"
    };

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder)
    {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        
        for (String coordinates : LIBRARIES)
            resolver.addDependency(new Dependency(new DefaultArtifact(coordinates), null));

        resolver.addRepository(new RemoteRepository.Builder(
                                                            "central",
                                                            "default",
                                                            MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
                                                   .build());

        classpathBuilder.addLibrary(resolver);
    }
}
