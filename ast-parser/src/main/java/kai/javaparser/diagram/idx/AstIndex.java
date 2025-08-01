package kai.javaparser.diagram.idx;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import kai.javaparser.diagram.AstClassUtil;
import kai.javaparser.model.FileAstData;
import kai.javaparser.util.Util;

/**
 * 管理專案中所有類別的 AST 索引。
 * <p>
 * 它的核心功能是在第一次執行時，從解析好的 JSON 檔案建立一個
 * "類別 FQN -> AST 檔案路徑" 的對應關係。然後將這個索引序列化到一個快取檔案中。
 * 在後續執行時，程式會直接從快取檔案載入索引，從而避免了昂貴的檔案系統掃描和 JSON 解析過程，
 * 大幅提升了啟動速度。
 * </p>
 */
public class AstIndex {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String CACHE_FILE_NAME = "ast-index.cache";

    // 索引: 類別的 FQN -> 包含該類別 AST 的 JSON 檔案路徑
    private Map<String, Path> classToPathIndex;
    // 快取: JSON 檔案路徑 -> 已解析的 FileAstData 物件，避免重複讀取和反序列化 JSON
    private final Map<Path, FileAstData> astDataCache = new HashMap<>();

    private final Path astJsonDir;
    private final Path cacheFilePath;

    public AstIndex(Path astJsonDir) {
        this.astJsonDir = astJsonDir;
        this.cacheFilePath = astJsonDir.resolve(CACHE_FILE_NAME);
        this.classToPathIndex = new HashMap<>();
    }

    /**
     * 載入或建立 AST 索引。
     * TODO 待修正CACHE 使用時會錯的問題。
     * <p>
     * 此方法會先檢查快取檔案是否存在。如果存在，則從快取載入索引；
     * 否則，掃描 JSON 目錄來建立一個新的索引，並將其儲存到快取以供將來使用。
     * </p>
     *
     * @throws IOException 如果檔案讀寫或序列化/反序列化失敗。
     * @throws ClassNotFoundException 如果從快取檔案反序列化時找不到對應的類別。
     */
    public void loadOrBuild() throws IOException, ClassNotFoundException {
        // if (Files.exists(cacheFilePath)) {
        //     System.out.println("偵測到索引快取檔案，正在載入...");
        //     loadFromCache();
        //     System.out.println("索引載入完成。");
        // } else {
            System.out.println("未找到索引快取，正在從 JSON 檔案建立索引...");
            buildFromFileSystem();
            saveToCache();
            System.out.println("索引建立並快取完成。");
        // }
        System.out.println("索引中包含 " + classToPathIndex.size() + " 個類別。");
    }

    @SuppressWarnings("unchecked")
    private void loadFromCache() throws IOException, ClassNotFoundException {
        this.classToPathIndex = Util.readJson(cacheFilePath, Map.class);
    }

    private void saveToCache() throws IOException {
        Util.writeJson(cacheFilePath, this.classToPathIndex);
    }

    private void buildFromFileSystem() throws IOException {
        try (Stream<Path> paths = Files.walk(astJsonDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".json"))
                 .forEach(jsonFile -> {
                    FileAstData astData = getAstDataFromFile(jsonFile);
                    // 假設一個 Java 檔案只定義一個 public 頂層類別
                    astData.findTopLevelClassFqn().ifPresent(classFqn -> {
                        classToPathIndex.put(classFqn, jsonFile);
                    });                
                 });
        }
    }

    /**
     * 根據類別的 FQN 取得對應的 FileAstData。
     * 此方法會優先從記憶體快取中讀取，如果不存在，則從磁碟讀取 JSON 檔案並解析。
     *
     * @param classFqn 類別的完整限定名
     * @return FileAstData 物件，如果找不到則為 null。
     */
    public FileAstData getAstDataByClassFqn(String classFqn) {
        Path path = classToPathIndex.get(classFqn);
        if (path == null) {
            return null;
        }
        // computeIfAbsent 確保對同一個檔案，只會讀取和解析一次
        return astDataCache.computeIfAbsent(path, this::getAstDataFromFile);
    }

    /**
     * 檢查指定的方法是否為其類別中某個欄位的 getter 或 setter。
     *
     * @param methodFqn 要檢查的方法的完整限定名
     * @return 如果是 getter/setter 則返回 true
     */
    public boolean isGetterSetter(String methodFqn) {
        String classFqn = AstClassUtil.getClassFqnFromMethodFqn(methodFqn);
        String methodName = AstClassUtil.getMethodSignature(methodFqn)
                                        .replaceAll("\\(.*\\)", ""); // 移除參數部分

        if (!methodName.startsWith("get") && !methodName.startsWith("set")) {
            return false;
        }

        FileAstData astData = getAstDataByClassFqn(classFqn);
        if (astData == null) {
            return false;
        }
        
        // 將 get/set 後面的名稱轉換為欄位名稱 (首字母小寫)
        String potentialFieldName = methodName.substring(3);
        if (potentialFieldName.isEmpty()) {
            return false;
        }
        potentialFieldName = Character.toLowerCase(potentialFieldName.charAt(0)) + potentialFieldName.substring(1);

        return astData.hasField(potentialFieldName);
    }

    private FileAstData getAstDataFromFile(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), FileAstData.class);
        } catch (IOException e) {
            System.err.println("讀取或解析 AST 檔案失敗: " + path);
            return null;
        }
    }
}