/* Decompiler 88ms, total 294ms, lines 372 */
package gg.essential.loader.stage2;

import gg.essential.loader.stage2.relaunch.Relaunch;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

public class EssentialLoader extends EssentialLoaderBase {
   private static final Logger LOGGER = LogManager.getLogger(EssentialLoader.class);
   private static final String MIXIN_TWEAKER = "org.spongepowered.asm.launch.MixinTweaker";
   private URL ourMixinUrl;

   public EssentialLoader(Path gameDir, String gameVersion) {
      super(gameDir, gameVersion, false);
   }

   protected void loadPlatform() {
      try {
         injectMixinTweaker();
      } catch (IllegalAccessException | InstantiationException | IOException | ClassNotFoundException var2) {
         throw new RuntimeException(var2);
      }
   }

   protected void addToClasspath(File file) {
      Path path = file.toPath();

      URL url;
      try {
         url = file.toURI().toURL();
         Launch.classLoader.addURL(url);
         ClassLoader classLoader = Launch.classLoader.getClass().getClassLoader();
         Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
         method.setAccessible(true);
         method.invoke(classLoader, url);
      } catch (Exception var30) {
         throw new RuntimeException("Unexpected error", var30);
      }

      this.ourMixinUrl = url;

      try {
         Field classLoaderExceptionsField = LaunchClassLoader.class.getDeclaredField("classLoaderExceptions");
         classLoaderExceptionsField.setAccessible(true);
         Set<String> classLoaderExceptions = (Set)classLoaderExceptionsField.get(Launch.classLoader);
         Field transformerExceptionsField = LaunchClassLoader.class.getDeclaredField("transformerExceptions");
         transformerExceptionsField.setAccessible(true);
         Set<String> transformerExceptions = (Set)transformerExceptionsField.get(Launch.classLoader);
         boolean kotlinExcluded = Stream.concat(classLoaderExceptions.stream(), transformerExceptions.stream()).anyMatch((prefix) -> {
            return prefix.startsWith("kotlin");
         });
         if (kotlinExcluded && !Relaunch.HAPPENED) {
            LOGGER.warn("Found Kotlin to be excluded from LaunchClassLoader transformations. This may cause issues.");
            LOGGER.debug("classLoaderExceptions:");
            Iterator var9 = classLoaderExceptions.iterator();

            String transformerException;
            while(var9.hasNext()) {
               transformerException = (String)var9.next();
               LOGGER.debug("  - {}", new Object[]{transformerException});
            }

            LOGGER.debug("transformerExceptions:");
            var9 = transformerExceptions.iterator();

            while(var9.hasNext()) {
               transformerException = (String)var9.next();
               LOGGER.debug("  - {}", new Object[]{transformerException});
            }

            if (Relaunch.ENABLED) {
               throw new EssentialLoader.RelaunchRequest();
            }
         }

         Field resourceCacheField = LaunchClassLoader.class.getDeclaredField("resourceCache");
         resourceCacheField.setAccessible(true);
         Map<String, byte[]> resourceCache = (Map)resourceCacheField.get(Launch.classLoader);
         Field negativeResourceCacheField = LaunchClassLoader.class.getDeclaredField("negativeResourceCache");
         negativeResourceCacheField.setAccessible(true);
         Set<String> negativeResourceCache = (Set)negativeResourceCacheField.get(Launch.classLoader);
         FileSystem fileSystem = FileSystems.newFileSystem(path, (ClassLoader)null);
         Throwable var14 = null;

         try {
            Path[] libs = new Path[]{fileSystem.getPath("kotlin"), fileSystem.getPath("kotlinx", "coroutines"), fileSystem.getPath("gg", "essential", "universal"), fileSystem.getPath("gg", "essential", "elementa"), fileSystem.getPath("gg", "essential", "vigilance"), fileSystem.getPath("codes", "som", "anthony", "koffee"), fileSystem.getPath("org", "kodein")};
            Path[] var16 = libs;
            int var17 = libs.length;

            for(int var18 = 0; var18 < var17; ++var18) {
               Path libPath = var16[var18];
               this.preloadLibrary(path, libPath, resourceCache, negativeResourceCache);
            }

            if (Launch.blackboard.get("mixin.initialised") == null) {
               this.preloadLibrary(path, fileSystem.getPath("org", "spongepowered"), resourceCache, negativeResourceCache);
            }
         } catch (Throwable var31) {
            var14 = var31;
            throw var31;
         } finally {
            if (fileSystem != null) {
               if (var14 != null) {
                  try {
                     fileSystem.close();
                  } catch (Throwable var29) {
                     var14.addSuppressed(var29);
                  }
               } else {
                  fileSystem.close();
               }
            }

         }

         if (Launch.classLoader.getClassBytes("pl.asie.foamfix.coremod.FoamFixCore") != null) {
            LOGGER.info("Detected FoamFix, locking LaunchClassLoader.resourceCache");
            resourceCacheField.set(Launch.classLoader, new ConcurrentHashMap<String, byte[]>(resourceCache) {
               public Set<Entry<String, byte[]>> entrySet() {
                  throw new RuntimeException("Suppressing FoamFix LaunchWrapper weak resource cache.") {
                     public void printStackTrace() {
                        EssentialLoader.LOGGER.info(this.getMessage());
                     }
                  };
               }
            });
         }
      } catch (EssentialLoader.RelaunchRequest var33) {
         Relaunch.relaunch(url);
      } catch (Exception var34) {
         LOGGER.error("Failed to pre-load dependencies: ", var34);
      }

   }

   private void preloadLibrary(Path jarPath, final Path libPath, final Map<String, byte[]> resourceCache, final Set<String> negativeResourceCache) throws IOException {
      if (Files.notExists(libPath, new LinkOption[0])) {
         LOGGER.debug("Not pre-loading {} because it does not exist.", new Object[]{libPath});
      } else {
         LOGGER.debug("Pre-loading {} from {}..", new Object[]{libPath, jarPath});
         long start = System.nanoTime();
         Files.walkFileTree(libPath, new SimpleFileVisitor<Path>() {
            private static final String SUFFIX = ".class";
            private boolean warned;

            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
               if (path.getFileName().toString().endsWith(".class")) {
                  String file = path.toString().substring(1);
                  String name = file.substring(0, file.length() - ".class".length()).replace('/', '.');
                  byte[] bytes = Files.readAllBytes(path);
                  byte[] oldBytes = (byte[])resourceCache.put(name, bytes);
                  if (oldBytes != null && !Arrays.equals(oldBytes, bytes) && !this.warned) {
                     this.warned = true;
                     EssentialLoader.LOGGER.warn("Found potentially conflicting version of {} already loaded. This may cause issues.", new Object[]{libPath});
                     EssentialLoader.LOGGER.warn("First conflicting class: {}", new Object[]{name});

                     try {
                        EssentialLoader.LOGGER.warn("Likely source: {}", new Object[]{Launch.classLoader.findResource(file)});
                     } catch (Throwable var8) {
                        EssentialLoader.LOGGER.warn("Unable to determine likely source:", var8);
                     }

                     if (Relaunch.ENABLED) {
                        throw new EssentialLoader.RelaunchRequest();
                     }
                  }

                  negativeResourceCache.remove(name);
               }

               return FileVisitResult.CONTINUE;
            }

            // $FF: synthetic method
            // $FF: bridge method
            public FileVisitResult visitFile(Object var1, BasicFileAttributes var2) throws IOException {
               return this.visitFile((Path)var1, var2);
            }
         });
         LOGGER.debug("Done after {}ns.", new Object[]{System.nanoTime() - start});
      }
   }

   protected boolean isInClassPath() {
      try {
         LinkedHashSet<String> objects = new LinkedHashSet();
         objects.add("gg.essential.api.tweaker.EssentialTweaker");
         Launch.classLoader.clearNegativeEntries(objects);
         Class.forName("gg.essential.api.tweaker.EssentialTweaker");
         return true;
      } catch (ClassNotFoundException var2) {
         return false;
      }
   }

   private static void injectMixinTweaker() throws ClassNotFoundException, IllegalAccessException, InstantiationException, IOException {
      List<String> tweakClasses = (List)Launch.blackboard.get("TweakClasses");
      if (!tweakClasses.contains("org.spongepowered.asm.launch.MixinTweaker")) {
         if (Launch.blackboard.get("mixin.initialised") == null) {
            System.out.println("Injecting MixinTweaker from EssentialLoader");
            Launch.classLoader.addClassLoaderExclusion("org.spongepowered.asm.launch.MixinTweaker".substring(0, "org.spongepowered.asm.launch.MixinTweaker".lastIndexOf(46)));
            List<ITweaker> tweaks = (List)Launch.blackboard.get("Tweaks");
            tweaks.add((ITweaker)Class.forName("org.spongepowered.asm.launch.MixinTweaker", true, Launch.classLoader).newInstance());
         }
      }
   }

   protected void doInitialize() {
      String outdatedMixin = this.isMixinOutdated();
      if (outdatedMixin != null) {
         LOGGER.warn("Found an old version of Mixin ({}). This may cause issues.", new Object[]{outdatedMixin});
         if (Relaunch.ENABLED) {
            Relaunch.relaunch(this.ourMixinUrl);
         }
      }

      super.doInitialize();
   }

   private String isMixinOutdated() {
      String loadedVersion = String.valueOf(Launch.blackboard.get("mixin.initialised"));
      String bundledVersion = this.getMixinVersion(this.ourMixinUrl);
      int[] loadedParts = this.parseMixinVersion(loadedVersion);
      int[] bundledParts = this.parseMixinVersion(bundledVersion);
      if (loadedParts != null && bundledParts != null) {
         for(int i = 0; i < loadedParts.length; ++i) {
            if (loadedParts[i] < bundledParts[i]) {
               return loadedVersion;
            }

            if (loadedParts[i] > bundledParts[i]) {
               break;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private int[] parseMixinVersion(String version) {
      if (version == null) {
         return null;
      } else {
         String[] parts = version.split("[.-]");
         int[] numbers = new int[3];

         for(int i = 0; i < parts.length && i < numbers.length; ++i) {
            try {
               numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException var6) {
               LOGGER.warn("Failed to parse mixin version \"{}\".", new Object[]{version});
               LOGGER.debug(var6);
               return null;
            }
         }

         return numbers;
      }
   }

   private String getMixinVersion(URL ourMixinUrl) {
      try {
         FileSystem fileSystem = FileSystems.newFileSystem(asJar(ourMixinUrl.toURI()), Collections.emptyMap());
         Throwable var3 = null;

         try {
            Path bootstrapPath = fileSystem.getPath("org", "spongepowered", "asm", "launch", "MixinBootstrap.class");
            InputStream inputStream = Files.newInputStream(bootstrapPath);
            Throwable var6 = null;

            try {
               ClassReader reader = new ClassReader(inputStream);
               ClassNode classNode = new ClassNode(327680);
               reader.accept(classNode, 0);
               Iterator var9 = classNode.fields.iterator();

               FieldNode field;
               do {
                  if (!var9.hasNext()) {
                     LOGGER.warn("Failed to determine version of bundled mixin: no VERSION field in MixinBootstrap");
                     return null;
                  }

                  field = (FieldNode)var9.next();
               } while(!field.name.equals("VERSION"));

               String var11 = String.valueOf(field.value);
               return var11;
            } catch (Throwable var39) {
               var6 = var39;
               throw var39;
            } finally {
               if (inputStream != null) {
                  if (var6 != null) {
                     try {
                        inputStream.close();
                     } catch (Throwable var38) {
                        var6.addSuppressed(var38);
                     }
                  } else {
                     inputStream.close();
                  }
               }

            }
         } catch (Throwable var41) {
            var3 = var41;
            throw var41;
         } finally {
            if (fileSystem != null) {
               if (var3 != null) {
                  try {
                     fileSystem.close();
                  } catch (Throwable var37) {
                     var3.addSuppressed(var37);
                  }
               } else {
                  fileSystem.close();
               }
            }

         }
      } catch (IOException | URISyntaxException var43) {
         LOGGER.warn("Failed to determine version of bundled mixin:", var43);
         return null;
      }
   }

   private static class RelaunchRequest extends RuntimeException {
      private RelaunchRequest() {
      }

      // $FF: synthetic method
      RelaunchRequest(Object x0) {
         this();
      }
   }
}
