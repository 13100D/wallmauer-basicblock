package de.uni_passau.fim.auermich.instrumentation.basicblockcoverage.utility;

import brut.androlib.ApkBuilder;
import brut.androlib.ApkDecoder;
import brut.androlib.Config;
import brut.androlib.exceptions.AndrolibException;
import brut.common.BrutException;
import brut.directory.DirectoryException;
import brut.directory.ExtFile;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.dexbacked.value.DexBackedTypeEncodedValue;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.smali.SmaliTestUtils;
import com.google.common.base.Charsets;
import com.google.common.io.ByteSource;
import de.uni_passau.fim.auermich.instrumentation.basicblockcoverage.BasicBlockCoverage;
import de.uni_passau.fim.auermich.instrumentation.basicblockcoverage.core.InstrumentationPoint;
import de.uni_passau.fim.auermich.instrumentation.basicblockcoverage.dto.MethodInformation;
import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.DexIO;
import lanchon.multidexlib2.MultiDexIO;
import org.antlr.runtime.RecognitionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class Utility {

    public static final String EXCLUSION_PATTERN_FILE = "exclude.txt";
    public static final String OUTPUT_BLOCKS_FILE = "blocks.txt";
    public static final String OUTPUT_BRANCHES_FILE = "branches.txt";
    public static final String SEPARATOR = "->";

    private static final Logger LOGGER = LogManager.getLogger(Utility.class);

    /**
     * It seems that certain resource classes are API dependent, e.g.
     * "R$interpolator" is only available in API 21.
     */
    private static final Set<String> resourceClasses = new HashSet<>() {{
        add("R$anim");
        add("R$attr");
        add("R$bool");
        add("R$color");
        add("R$dimen");
        add("R$drawable");
        add("R$id");
        add("R$integer");
        add("R$layout");
        add("R$mipmap");
        add("R$string");
        add("R$style");
        add("R$styleable");
        add("R$interpolator");
        add("R$menu");
        add("R$array");
    }};

    private Utility() {
        throw new UnsupportedOperationException("Utility class!");
    }

    /**
     * Checks whether the given class represents the dynamically generated R class or any
     * inner class of it.
     *
     * @param classDef The class to be checked.
     * @return Returns {@code true} if the given class represents the R class or any
     * inner class of it, otherwise {@code false} is returned.
     */
    public static boolean isResourceClass(ClassDef classDef) {

        String className = Utility.dottedClassName(classDef.toString());

        String[] tokens = className.split("\\.");

        // check whether it is the R class itself
        if (tokens[tokens.length - 1].equals("R")) {
            return true;
        }

        // check for inner R classes
        for (String resourceClass : resourceClasses) {
            if (className.contains(resourceClass)) {
                return true;
            }
        }

        // TODO: can be removed, just for illustration how to process annotations
        Set<? extends Annotation> annotations = classDef.getAnnotations();

        for (Annotation annotation : annotations) {

            // check if the enclosing class is the R class
            if (annotation.getType().equals("Ldalvik/annotation/EnclosingClass;")) {
                for (AnnotationElement annotationElement : annotation.getElements()) {
                    if (annotationElement.getValue() instanceof DexBackedTypeEncodedValue) {
                        DexBackedTypeEncodedValue value = (DexBackedTypeEncodedValue) annotationElement.getValue();
                        if (value.getValue().equals("Landroidx/appcompat/R;")) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Checks whether the given class represents the dynamically generated BuildConfig class.
     *
     * @param classDef The class to be checked.
     * @return Returns {@code true} if the given class represents the dynamically generated
     * BuildConfig class, otherwise {@code false} is returned.
     */
    public static boolean isBuildConfigClass(ClassDef classDef) {
        String className = Utility.dottedClassName(classDef.toString());
        // TODO: check solely the last token (the actual class name)
        return className.endsWith("BuildConfig");
    }

    /**
     * Loads the tracer functionality directly from smali files.
     *
     * @param apiLevel The api opcode level.
     * @return Returns the classes representing the tracer.
     */
    public static List<ClassDef> loadTracer(int apiLevel) {
        List<ClassDef> tracerClasses = new ArrayList<>();
        tracerClasses.add(loadClass(apiLevel, "Tracer.smali"));
        tracerClasses.add(loadClass(apiLevel, "Tracer$1.smali"));
        return tracerClasses;
    }

    /**
     * Loads a smali class from the resources folder.
     *
     * @param apiLevel The api level of the smali class.
     * @param className The smali class name.
     * @return Returns the loaded class.
     */
    public static ClassDef loadClass(int apiLevel, String className) {

        InputStream inputStream = BasicBlockCoverage.class.getClassLoader().getResourceAsStream(className);

        ByteSource byteSource = new ByteSource() {
            @Override
            public InputStream openStream() throws IOException {
                return inputStream;
            }
        };

        try {
            String smaliCode = byteSource.asCharSource(Charsets.UTF_8).read();
            return SmaliTestUtils.compileSmali(smaliCode, apiLevel);
        } catch (IOException | RecognitionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds a given APK using apktool.
     *
     * @param decodedAPKPath The root directory of the decoded APK.
     * @param outputFile The file path of the resulting APK. If {@code null}
     *                   is specified, the default location ('dist' directory)
     *                   and the original APK name is used.
     * @return Returns {@code true} if building the APK succeeded, otherwise {@code false} is returned.
     */
    public static boolean buildAPK(File decodedAPKPath, File outputFile) {

        final Config config = Config.getDefaultConfig();
        config.useAapt2 = true;
        config.verbose = true;

        try {
            new ApkBuilder(config, new ExtFile(decodedAPKPath)).build(outputFile);
            return true;
        } catch (BrutException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Decodes a given APK using apktool.
     */
    public static File decodeAPK(File apkPath) {

        // set 3rd party library (apktool) logging to 'SEVERE'
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
        rootLogger.setLevel(Level.SEVERE);
        for (Handler h : rootLogger.getHandlers()) {
            h.setLevel(Level.SEVERE);
        }

        final Config config = Config.getDefaultConfig();
        config.forceDelete = true; // overwrites existing dir: -f

        try {
            // do not decode dex classes to smali: -s
            config.setDecodeSources(Config.DECODE_SOURCES_NONE);

            /*
            * TODO: Right now we need to decode the resources completely although we only need to alter the manifest.
            *  While decoding only the manifest works and even re-packaging succeeds, the APK cannot be properly signed
            *  anymore: https://github.com/iBotPeaches/Apktool/issues/3389
             */

            // do not decode resources: -r
            // config.setDecodeResources(Config.DECODE_RESOURCES_NONE);

            // decode the manifest: --force-manifest
            // config.setForceDecodeManifest(Config.FORCE_DECODE_MANIFEST_FULL);

            // path where we want to decode the APK (the same directory as the APK)
            File parentDir = apkPath.getParentFile();
            File outputDir = new File(parentDir, "decodedAPK");

            LOGGER.debug("Decoding Output Dir: " + outputDir);

            final ApkDecoder decoder = new ApkDecoder(config, apkPath);
            decoder.decode(outputDir);
            return outputDir;
        } catch (AndrolibException | IOException | DirectoryException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Writes the number of instructions and number of branches per method. Methods which are not instrumented are omitted.
     *
     * @param methodInformation A description of the instrumented method.
     */
    public static synchronized void writeBasicBlocks(final MethodInformation methodInformation) {

        File file = new File(OUTPUT_BLOCKS_FILE);

        try (OutputStream outputStream = new FileOutputStream(file, true);
             PrintStream printStream = new PrintStream(outputStream)) {

            Set<InstrumentationPoint> instrumentationPoints = new TreeSet<>(methodInformation.getInstrumentationPoints());

            if (instrumentationPoints.size() > 0) {

                final String method = methodInformation.getMethodID();

                for (InstrumentationPoint instrumentationPoint : instrumentationPoints) {
                    int basicBlockID = instrumentationPoint.getPosition();
                    int basicBlockSize = instrumentationPoint.getCoveredInstructions();
                    String isBranch = instrumentationPoint.hasBranchType() ? "isBranch" : "noBranch";
                    // The timestamp will be appended by the tracer at runtime
                    printStream.println(method + SEPARATOR + basicBlockID + SEPARATOR + basicBlockSize + SEPARATOR + isBranch);
                }
                printStream.flush();
            }
        } catch (IOException e) {
            LOGGER.error("Couldn't write basic blocks to blocks.txt");
            throw new IllegalStateException("Couldn't write basic blocks to blocks.txt");
        }
    }

    /**
     * Writes out the branches of method. Methods without any branches are omitted.
     *
     * @param methodInformation Encapsulates a method.
     */
    public static synchronized void writeBranches(MethodInformation methodInformation) {

        File file = new File(OUTPUT_BRANCHES_FILE);

        try (OutputStream outputStream = new FileOutputStream(file, true);
             PrintStream printStream = new PrintStream(outputStream)) {

            Set<InstrumentationPoint> instrumentationPoints = new TreeSet<>(methodInformation.getInstrumentationPoints());

            if (instrumentationPoints.size() > 0) {

                final String method = methodInformation.getMethodID();

                for (InstrumentationPoint instrumentationPoint : instrumentationPoints) {

                    if (instrumentationPoint.hasBranchType()) {
                        printStream.println(method + SEPARATOR + instrumentationPoint.getPosition());
                    }
                }
            }

            printStream.flush();
        } catch (IOException e) {
            LOGGER.error("Couldn't write branches to branches.txt");
            throw new IllegalStateException("Couldn't write branches to branches.txt");
        }
    }

    /**
     * Writes the number of instructions and number of branches per method.
     * Methods which are not instrumented are omitted.
     *
     * @param methodInformation A description of the instrumented method.
     * @throws FileNotFoundException Should never be thrown.
     */
    @SuppressWarnings("unused")
    public static void writeInstructionAndBranchCount(final MethodInformation methodInformation) throws FileNotFoundException {

        File file = new File(OUTPUT_BLOCKS_FILE);
        OutputStream outputStream = new FileOutputStream(file, true);
        PrintStream printStream = new PrintStream(outputStream);

        if (methodInformation.getInstrumentationPoints().size() > 0) {
            final String method = methodInformation.getMethod().toString();
            final int instructionCount = methodInformation.getInitialInstructionCount();
            final int numberOfBranches = methodInformation.getNumberOfBranches();
            printStream.println(method + SEPARATOR + instructionCount + SEPARATOR + numberOfBranches);
            printStream.flush();
        }
        printStream.close();
    }

    /**
     * Transforms a class name containing '/' into a class name with '.'
     * instead, and removes the leading 'L' as well as the ';' at the end.
     *
     * @param className The class name which should be transformed.
     * @return The transformed class name.
     */
    public static String dottedClassName(String className) {
        className = className.substring(className.indexOf('L') + 1, className.indexOf(';'));
        className = className.replace('/', '.');
        return className;
    }

    /**
     * Generates patterns of classes which should be excluded from the instrumentation.
     *
     * @return The pattern representing classes that should not be instrumented.
     * @throws IOException        If the file containing excluded classes is not available.
     */
    public static Pattern readExcludePatterns() throws IOException {

        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(EXCLUSION_PATTERN_FILE);

        if (inputStream == null) {
            LOGGER.debug("Couldn't find exclusion file!");
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            if (first)
                first = false;
            else
                builder.append("|");
            builder.append(line);
        }
        reader.close();
        return Pattern.compile(builder.toString());
    }

    /**
     * Writes a merged dex file to a directory. Under the scene, the dex file is split
     * into multiple dex files if the method reference limit would be violated.
     *
     * @param filePath The directory where the dex files should be written to.
     * @param classes The classes that should be contained within the dex file.
     * @param opCode The API opcode level, e.g. API 28 (Android).
     * @throws IOException Should never happen.
     */

    public static void writeMultiDexFile(File filePath, List<ClassDef> classes, int opCode) throws IOException {

        DexFile dexFile = new DexFile() {
            @Nonnull
            @Override
            public Set<? extends ClassDef> getClasses() {
                return new AbstractSet<ClassDef>() {
                    @Nonnull
                    @Override
                    public Iterator<ClassDef> iterator() {
                        return classes.iterator();
                    }

                    @Override
                    public int size() {
                        return classes.size();
                    }
                };
            }

            @Nonnull
            @Override
            public Opcodes getOpcodes() {
                return Opcodes.forApi(opCode);
            }
        };

        // Create the output map that will hold the dex data in memory
        Map<String, MemoryDataStore> outputMap = new LinkedHashMap<>();

        // Write to memory using MultiDexIO (it will split automatically if needed)
        MultiDexIO.writeDexFile(
                true,                           // multiDex = true (allow splitting)
                outputMap,                      // output map to store dex data
                new BasicDexFileNamer(),        // namer for classes.dex, classes2.dex, etc.
                dexFile,                        // the DexFile to write
                DexIO.DEFAULT_MAX_DEX_POOL_SIZE, // max pool size
                null                            // logger (optional)
        );

        // Now write the memory stores to actual files on disk
        File outputDir = filePath.isDirectory() ? filePath : filePath.getParentFile();
        
        for (Map.Entry<String, MemoryDataStore> entry : outputMap.entrySet()) {
            String dexFileName = entry.getKey();
            MemoryDataStore dataStore = entry.getValue();
            
            File outputFile = new File(outputDir, dexFileName);
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                // Get the buffer from MemoryDataStore and write it
                byte[] buffer = dataStore.getBuffer();
                int size = dataStore.getSize();
                fos.write(buffer, 0, size);
            }
        }
    }
    // public static void writeMultiDexFile(File filePath, List<ClassDef> classes, int opCode) throws IOException {

    //     // TODO: directly update merged dex file instance instead of creating new dex file instance here
    //     DexFile dexFile = new DexFile() {
    //         @Nonnull
    //         @Override
    //         public Set<? extends ClassDef> getClasses() {
    //             return new AbstractSet<ClassDef>() {
    //                 @Nonnull
    //                 @Override
    //                 public Iterator<ClassDef> iterator() {
    //                     return classes.iterator();
    //                 }

    //                 @Override
    //                 public int size() {
    //                     return classes.size();
    //                 }
    //             };
    //         }

    //         @Nonnull
    //         @Override
    //         public Opcodes getOpcodes() {
    //             // https://android.googlesource.com/platform/dalvik/+/master/dx/src/com/android/dex/DexFormat.java
    //             return Opcodes.forApi(opCode);
    //         }
    //     };

    //     // Create a map to store the dex data
    //     Map<String, MemoryDataStore> dexDataStoreMap = new HashMap<>();

    //     // Write the dex file to the memory store
    //     MemoryDataStore memoryStore = new MemoryDataStore();
    //     DexIO.writeDexFile(memoryStore, dexFile);

    //     // Put it in the map with a key (typically the dex file name)
    //     dexDataStoreMap.put(filePath.getName(), memoryStore);

    //     // Now write to disk using MultiDexIO
    //     MultiDexIO.writeDexFile(true, 0, dexDataStoreMap, new BasicDexFileNamer(),
    //             filePath, DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null);


    //     // MultiDexIO.writeDexFile(true, 0, filePath, new BasicDexFileNamer(),
    //     //         dexFile, DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null);
    // }

    /**
     * Produces a .dex file containing the given list of classes.
     *
     * @param filePath The path of the .dex file.
     * @param classes The list of classes that should be contained in the .dex file.
     * @param opCode The API opcode level.
     * @throws IOException Should never happen.
     */
    @SuppressWarnings("unused")
    public static void writeToDexFile(String filePath, List<ClassDef> classes, int opCode) throws IOException {

        DexFileFactory.writeDexFile(filePath, new DexFile() {
            @Nonnull
            @Override
            public Set<? extends ClassDef> getClasses() {
                return new AbstractSet<ClassDef>() {
                    @Nonnull
                    @Override
                    public Iterator<ClassDef> iterator() {
                        return classes.iterator();
                    }

                    @Override
                    public int size() {
                        return classes.size();
                    }
                };
            }

            @Nonnull
            @Override
            public Opcodes getOpcodes() {
                return Opcodes.forApi(opCode);
            }
        });
    }

    /**
     * Increases the register directive of the method, i.e. the .register statement at the method head
     * according to the number specified by {@param newRegisterCount}.
     *
     * @param methodInformation Stores all relevant information about a method.
     * @param newRegisterCount      The new amount of registers the method should have.
     * @throws NoSuchFieldException   Should never happen - a byproduct of reflection.
     * @throws IllegalAccessException Should never happen - a byproduct of reflection.
     * @return Returns the modified implementation.
     *
     */
    public static MethodImplementation increaseMethodRegisterCount(MethodInformation methodInformation, int newRegisterCount) {

        MethodImplementation methodImplementation = methodInformation.getMethodImplementation();
        MutableMethodImplementation mutableImplementation = new MutableMethodImplementation(methodImplementation);

        try {
            java.lang.reflect.Field f = mutableImplementation.getClass().getDeclaredField("registerCount");
            f.setAccessible(true);
            f.set(mutableImplementation, newRegisterCount);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        // update implementation
        methodInformation.setMethodImplementation(mutableImplementation);
        return mutableImplementation;
    }

    /**
     * Adds the modified method implementation to the list of methods that are written to
     * the instrumented dex file.
     *
     * @param methods The list of methods included in the final dex file.
     * @param methodInformation Stores all relevant information about a method.
     */
    public static void addInstrumentedMethod(List<Method> methods, MethodInformation methodInformation) {

        Method method = methodInformation.getMethod();
        MethodImplementation modifiedImplementation = methodInformation.getMethodImplementation();

        methods.add(new ImmutableMethod(
                method.getDefiningClass(),
                method.getName(),
                method.getParameters(),
                method.getReturnType(),
                method.getAccessFlags(),
                method.getAnnotations(),
                null, // necessary for dexlib2 2.4.0
                modifiedImplementation));
    }

    /**
     * Adds the given class (#param classDef} including its method to the list of classes
     * that are part of the final dex file.
     *
     * @param classes The list of classes part of the final dex file.
     * @param methods The list of methods belonging to the given class.
     * @param classDef The class we want to add.
     */
    public static void addInstrumentedClass(List<ClassDef> classes, List<Method> methods, ClassDef classDef) {

        classes.add(new ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags(),
                classDef.getSuperclass(),
                classDef.getInterfaces(),
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                classDef.getFields(),
                methods));
    }

    /**
     * Increasing the amount of registers for a method requires shifting
     * certain registers back to their original position. In particular, all
     * registers, which register id is bigger or equal than the new specified
     * register count {@param registerNumber}, need to be shifted (increased) by the amount of the newly
     * created registers. Especially, originally param registers are affected
     * by increasing the register count, and would be treated now as a local register
     * without re-ordering (shifting).
     *
     * @param instruction    The instruction that is currently inspected.
     * @param registerNumber Specifies a lower limit for registers, which need to be considered.
     * @throws NoSuchFieldException   Should never happen, constitutes a byproduct of using reflection.
     * @throws IllegalAccessException Should never happen, constitutes a byproduct of using reflection.
     */
    @SuppressWarnings("unused")
    public static void reOrderRegister(BuilderInstruction instruction, int registerNumber, int shift)
            throws NoSuchFieldException, IllegalAccessException {

        // those invoke range instructions require a special treatment, since they don't have fields containing the registers
        if (instruction instanceof BuilderInstruction3rc) {

            // those instructions store the number of registers (var registerCount) and the first register of these range (var startRegister)
            int registerStart = ((BuilderInstruction3rc) instruction).getStartRegister();
            java.lang.reflect.Field f = instruction.getClass().getDeclaredField("startRegister");
            if (registerStart >= registerNumber) {
                f.setAccessible(true);
                f.set(instruction, registerStart + shift);
            }
            return;
        }

        java.lang.reflect.Field[] fields = instruction.getClass().getDeclaredFields();

        for (java.lang.reflect.Field field : fields) {
            // all fields are labeled registerA - registerG
            if (field.getName().startsWith("register") && !field.getName().equals("registerCount")) {
                field.setAccessible(true);
                // System.out.println(field.getName());
                try {
                    int value = field.getInt(instruction);

                    if (value >= registerNumber)
                        field.set(instruction, value + shift);

                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Determines whether the given class represents a core application class.
     *
     * @param packageName The package name of the app.
     * @param className The class to be checked.
     * @return Returns {@code true} if the given class represents an application class, otherwise {@code false}.
     */
    public static boolean isApplicationClass(final String packageName, final String className) {
        if (className.startsWith(packageName)) {
            return true;
        } else {
            // Heuristic: At the least the first two packages must match.
            final String[] packages = packageName.split("\\.");
            if (packages.length < 2) {
                return false;
            } else {
                return className.startsWith(packages[0] + "." + packages[1]);
            }
        }
    }
}
