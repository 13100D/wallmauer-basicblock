# Basic Block Coverage Tool

This is a minimal version of the Basic Block Coverage instrumentation tool. It instruments Android APKs to track basic block coverage during execution.

## Requirements

- Java 17
- Gradle (wrapper included)

## Building

To build the tool:

```bash
./gradlew customFatJar
```

This will create `basicBlockCoverage.jar` in the `build/libs` directory.

## Usage

```bash
java -jar build/libs/basicBlockCoverage.jar <path-to-apk>
```

The tool will:
1. Instrument the APK to track basic block coverage
2. Generate `blocks.txt` containing basic block information
3. Create an instrumented version of the APK with the suffix "_instrumented.apk" 
