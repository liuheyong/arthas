package com.taobao.arthas.agent.attach;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.zeroturnaround.zip.ZipUtil;

import java.arthas.SpyAPI;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * @author hengyunabc 2020-06-22
 */
public class ArthasAgent {

    private static final int TEMP_DIR_ATTEMPTS = 10000;

    private static final String ARTHAS_CORE_JAR = "arthas-core.jar";
    private static final String ARTHAS_BOOTSTRAP = "com.taobao.arthas.core.server.ArthasBootstrap";
    private static final String GET_INSTANCE = "getInstance";
    private static final String IS_BIND = "isBind";

    private String errorMessage;

    private Map<String, String> configMap = new HashMap<String, String>();
    private String arthasHome;
    private boolean slientInit;
    private Instrumentation instrumentation;

    public ArthasAgent() {
        this(null, null, false, null);
    }

    public ArthasAgent(Map<String, String> configMap) {
        this(configMap, null, false, null);
    }

    public ArthasAgent(String arthasHome) {
        this(null, arthasHome, false, null);
    }

    public ArthasAgent(Map<String, String> configMap, String arthasHome, boolean slientInit,
                       Instrumentation instrumentation) {
        if (configMap != null) {
            this.configMap = configMap;
        }

        this.arthasHome = arthasHome;
        this.slientInit = slientInit;
        this.instrumentation = instrumentation;
    }

    public static void attach() {
        new ArthasAgent().init();
    }

    /**
     * @param configMap
     * @see https://arthas.aliyun.com/doc/arthas-properties.html
     */
    public static void attach(Map<String, String> configMap) {
        new ArthasAgent(configMap).init();
    }

    /**
     * use the specified arthas
     *
     * @param arthasHome arthas directory
     */
    public static void attach(String arthasHome) {
        new ArthasAgent().init();
    }

    private static File createTempDir() {
        File baseDir = new File(System.getProperty("java.io.tmpdir"));
        String baseName = "arthas-" + System.currentTimeMillis() + "-";

        for (int counter = 0; counter < TEMP_DIR_ATTEMPTS; counter++) {
            File tempDir = new File(baseDir, baseName + counter);
            if (tempDir.mkdir()) {
                return tempDir;
            }
        }
        throw new IllegalStateException("Failed to create directory within " + TEMP_DIR_ATTEMPTS + " attempts (tried "
                + baseName + "0 to " + baseName + (TEMP_DIR_ATTEMPTS - 1) + ')');
    }

    public void init() throws IllegalStateException {
        // ????????????arthas??????????????????????????????????????????????????????
        try {
            Class.forName("java.arthas.SpyAPI"); // ????????????????????????
            if (SpyAPI.isInited()) {
                return;
            }
        } catch (Throwable e) {
            // ignore
        }

        try {
            if (instrumentation == null) {
                instrumentation = ByteBuddyAgent.install();
            }

            // ?????? arthasHome
            if (arthasHome == null || arthasHome.trim().isEmpty()) {
                // ????????? arthasHome
                URL coreJarUrl = this.getClass().getClassLoader().getResource("arthas-bin.zip");
                if (coreJarUrl != null) {
                    File tempArthasDir = createTempDir();
                    ZipUtil.unpack(coreJarUrl.openStream(), tempArthasDir);
                    arthasHome = tempArthasDir.getAbsolutePath();
                } else {
                    throw new IllegalArgumentException("can not getResources arthas-bin.zip from classloader: "
                            + this.getClass().getClassLoader());
                }
            }

            // find arthas-core.jar
            File arthasCoreJarFile = new File(arthasHome, ARTHAS_CORE_JAR);
            if (!arthasCoreJarFile.exists()) {
                throw new IllegalStateException("can not find arthas-core.jar under arthasHome: " + arthasHome);
            }
            AttachArthasClassloader arthasClassLoader = new AttachArthasClassloader(
                    new URL[]{arthasCoreJarFile.toURI().toURL()});

            /**
             * <pre>
             * ArthasBootstrap bootstrap = ArthasBootstrap.getInstance(inst);
             * </pre>
             */
            Class<?> bootstrapClass = arthasClassLoader.loadClass(ARTHAS_BOOTSTRAP);
            Object bootstrap = bootstrapClass.getMethod(GET_INSTANCE, Instrumentation.class, Map.class).invoke(null,
                    instrumentation, configMap);
            boolean isBind = (Boolean) bootstrapClass.getMethod(IS_BIND).invoke(bootstrap);
            if (!isBind) {
                String errorMsg = "Arthas server port binding failed! Please check $HOME/logs/arthas/arthas.log for more details.";
                throw new RuntimeException(errorMsg);
            }
        } catch (Throwable e) {
            errorMessage = e.getMessage();
            if (!slientInit) {
                throw new IllegalStateException(e);
            }
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
