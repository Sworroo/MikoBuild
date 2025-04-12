package me.sworroo;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.selections.Selection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.Block;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MikoBuildPlugin extends JavaPlugin {

    // API LM Studio по умолчанию
    private String lmStudioUrl = "http://localhost:1234/v1/chat/completions";
    private int animationTaskId = -1;
    private final Map<String, Material> originalBlocks = new HashMap<>();
    private final Map<String, Material> materialCache = new ConcurrentHashMap<>();
    private final Set<UUID> activeBuilders = new HashSet<>();

    @Override
    public void onEnable() {
        getLogger().info("MikoBuild плагин активирован!");

        // Сохраняем конфигурацию по умолчанию, если она не существует
        saveDefaultConfig();

        // Проверяем наличие WorldEdit
        if (getServer().getPluginManager().getPlugin("WorldEdit") == null) {
            getLogger().severe("WorldEdit не найден! Плагин MikoBuild требует WorldEdit.");
        }

        // Проверяем доступность LM Studio API
        testLmStudioConnection();
    }
    private void testLmStudioConnection() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // Проверим, работает ли LM Studio API
                URL url = new URL(lmStudioUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);  // 3 секунды на соединение

                // Отправляем минимальный запрос для проверки
                String testRequest = "{\"model\":\"local-model\",\"messages\":[{\"role\":\"user\",\"content\":\"test\"}],\"max_tokens\":10}";

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = testRequest.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    getLogger().info("Успешно подключились к LM Studio API!");
                } else {
                    getLogger().warning("LM Studio API доступен, но вернул код ошибки: " + responseCode);
                    getLogger().warning("Убедитесь, что LM Studio запущен и локальный сервер активирован.");
                }
            } catch (Exception e) {
                getLogger().severe("Не удалось подключиться к LM Studio API: " + e.getMessage());
                getLogger().severe("Убедитесь, что LM Studio запущен и локальный сервер работает на " + lmStudioUrl);
                getLogger().severe("Инструкция по настройке:");
                getLogger().severe("1. Запустите LM Studio");
                getLogger().severe("2. Загрузите модель (например, Llama3-8B)");
                getLogger().severe("3. Перейдите на вкладку 'Local Server'");
                getLogger().severe("4. Нажмите 'Start Server' и убедитесь, что включена опция 'OpenAI Compatible'");
            }
        });
    }

    @Override
    public void onDisable() {
        // Останавливаем все анимации
        if (animationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(animationTaskId);
        }
        getLogger().info("MikoBuild плагин деактивирован!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("miko")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cЭта команда доступна только для игроков!");
                return true;
            }

            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();

            // Проверка, не выполняет ли игрок уже задание
            if (activeBuilders.contains(playerId)) {
                player.sendMessage("§cВы уже создаете постройку. Пожалуйста, дождитесь завершения.");
                return true;
            }

            // Соединяем аргументы в одну строку запроса
            StringBuilder prompt = new StringBuilder();
            for (String arg : args) {
                prompt.append(arg).append(" ");
            }

            if (prompt.length() == 0) {
                player.sendMessage("§cИспользование: /miko [запрос на постройку]");
                player.sendMessage("§cСначала выделите регион с помощью WorldEdit!");
                return true;
            }

            // Получаем выделенный регион WorldEdit
            WorldEditPlugin worldEdit = (WorldEditPlugin) Bukkit.getServer().getPluginManager().getPlugin("WorldEdit");
            Selection selection;

            try {
                selection = worldEdit.getSelection(player);

                if (selection == null) {
                    player.sendMessage("§cСначала выделите регион с помощью WorldEdit!");
                    return true;
                }

                Location min = selection.getMinimumPoint();
                Location max = selection.getMaximumPoint();

                // Получаем размеры региона
                int width = max.getBlockX() - min.getBlockX() + 1;
                int height = max.getBlockY() - min.getBlockY() + 1;
                int length = max.getBlockZ() - min.getBlockZ() + 1;

                // Проверяем, не слишком ли большой регион
                int volume = width * height * length;
                if (volume > 10000) { // Ограничение размера для производительности
                    player.sendMessage("§cВыбранный регион слишком большой! Максимальный объем: 10000 блоков.");
                    player.sendMessage("§cТекущий объем: " + volume + " блоков.");
                    return true;
                }

                player.sendMessage("§aНачинаю создание по запросу: §6" + prompt);
                player.sendMessage("§aРазмер региона: §b" + width + "x" + height + "x" + length);

                // Добавляем игрока в список активных строителей
                activeBuilders.add(playerId);

                // Сохраняем оригинальные блоки перед анимацией
                saveOriginalBlocks(selection, player.getWorld().getName());

                // Запускаем анимацию построения
                startBuildingAnimation(selection, player.getWorld().getName());

                // Запрашиваем структуру у LM Studio асинхронно
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        String structureData = requestStructureFromLmStudio(
                                prompt.toString(),
                                width,
                                height,
                                length
                        );

                        // Строим структуру в основном потоке
                        Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                // Останавливаем анимацию
                                stopBuildingAnimation();

                                // Восстанавливаем оригинальные блоки перед постройкой
                                restoreOriginalBlocks(player.getWorld().getName());

                                // Строим новую структуру
                                buildStructure(structureData, min, player.getWorld().getName(), player);
                                player.sendMessage("§aПостройка завершена!");
                            } catch (Exception e) {
                                // Восстанавливаем оригинальные блоки в случае ошибки
                                restoreOriginalBlocks(player.getWorld().getName());
                                player.sendMessage("§cОшибка при создании структуры: " + e.getMessage());
                                e.printStackTrace();
                            } finally {
                                // Удаляем игрока из списка активных строителей
                                activeBuilders.remove(playerId);
                            }
                        });

                    } catch (Exception e) {
                        // Останавливаем анимацию и восстанавливаем блоки в случае ошибки
                        Bukkit.getScheduler().runTask(this, () -> {
                            stopBuildingAnimation();
                            restoreOriginalBlocks(player.getWorld().getName());
                            player.sendMessage("§cПроизошла ошибка при запросе к LM Studio: " + e.getMessage());

                            // Удаляем игрока из списка активных строителей
                            activeBuilders.remove(playerId);
                        });

                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                player.sendMessage("§cОшибка при получении выделенного региона: " + e.getMessage());
                e.printStackTrace();
                return true;
            }

            return true;
        }
        return false;
    }

    private void saveOriginalBlocks(Selection selection, String worldName) {
        originalBlocks.clear();
        org.bukkit.World world = Bukkit.getWorld(worldName);

        try {
            Location min = selection.getMinimumPoint();
            Location max = selection.getMaximumPoint();

            for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
                for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                    for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                        String key = x + ":" + y + ":" + z;
                        originalBlocks.put(key, world.getBlockAt(x, y, z).getType());
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("Ошибка при сохранении оригинальных блоков: " + e.getMessage());
        }
    }

    private void restoreOriginalBlocks(String worldName) {
        org.bukkit.World world = Bukkit.getWorld(worldName);

        for (Map.Entry<String, Material> entry : originalBlocks.entrySet()) {
            String[] coords = entry.getKey().split(":");
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int z = Integer.parseInt(coords[2]);

            Block block = world.getBlockAt(x, y, z);
            block.setType(entry.getValue());
        }

        originalBlocks.clear();
    }

    private void startBuildingAnimation(Selection selection, String worldName) {
        org.bukkit.World world = Bukkit.getWorld(worldName);

        // Материалы для анимации - красивые и яркие блоки для Minecraft 1.12.2
        final Material[] animationMaterials = new Material[] {
                Material.GOLD_BLOCK, Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK,
                Material.IRON_BLOCK, Material.REDSTONE_BLOCK, Material.LAPIS_BLOCK,
                Material.GLOWSTONE, Material.QUARTZ_BLOCK, Material.WOOL,
                Material.STAINED_GLASS, Material.SEA_LANTERN, Material.PURPUR_BLOCK
        };

        final Random random = new Random();
        final Location min = selection.getMinimumPoint();
        final Location max = selection.getMaximumPoint();

        animationTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            try {
                // Количество блоков для изменения за один тик (примерно 5% от всех блоков в регионе)
                int totalVolume = (max.getBlockX() - min.getBlockX() + 1) *
                        (max.getBlockY() - min.getBlockY() + 1) *
                        (max.getBlockZ() - min.getBlockZ() + 1);
                int numBlocksToChange = Math.max(1, totalVolume / 20);

                // Ограничиваем максимальное число изменений для производительности
                numBlocksToChange = Math.min(100, numBlocksToChange);

                for (int i = 0; i < numBlocksToChange; i++) {
                    int x = min.getBlockX() + random.nextInt(max.getBlockX() - min.getBlockX() + 1);
                    int y = min.getBlockY() + random.nextInt(max.getBlockY() - min.getBlockY() + 1);
                    int z = min.getBlockZ() + random.nextInt(max.getBlockZ() - min.getBlockZ() + 1);

                    Block block = world.getBlockAt(x, y, z);
                    Material material = animationMaterials[random.nextInt(animationMaterials.length)];

                    block.setType(material);
                }
            } catch (Exception e) {
                getLogger().severe("Ошибка в анимации: " + e.getMessage());
                stopBuildingAnimation();
            }
        }, 0L, 4L); // Запуск каждые 4 тика (примерно 0.2 секунды)
    }

    private void stopBuildingAnimation() {
        if (animationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(animationTaskId);
            animationTaskId = -1;
        }
    }
    private String requestStructureFromLmStudio(String prompt, int width, int height, int length) throws Exception {
        URL url = new URL(lmStudioUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(300000);

        // Улучшенный системный промпт для создания реалистичных построек
        String systemPrompt = String.format(
                "Ты minecraft-архитектор, специализирующийся на создании трехмерных структур. " +
                        "Создай JSON для полноценной трехмерной постройки по запросу пользователя. " +
                        "\n\nПРАВИЛА СОЗДАНИЯ СТРУКТУР:" +
                        "\n1. Создавай ПОЛНОЦЕННЫЕ ТРЕХМЕРНЫЕ ЗДАНИЯ с внешними стенами, внутренними помещениями, крышей и полом" +
                        "\n2. Размеры: ширина=%d, высота=%d, глубина=%d" +
                        "\n3. Обязательно используй полный диапазон оси Z (0-%d) для создания объема" +
                        "\n4. Постройка должна иметь: внешние стены, внутренние перегородки, пол, крышу, окна, двери" +
                        "\n5. Формат блоков: {\"x\":число,\"y\":число,\"z\":число,\"material\":\"ТИП_БЛОКА\"}" +
                        "\n6. Координаты: 0≤x<%d, 0≤y<%d, 0≤z<%d" +
                        "\n7. ВАЖНО: Создавай законченные строения, а не отдельные стены" +
                        "\n8. ВАЖНО: Используй ТОЛЬКО поле 'material' для указания типа блока" +
                        "\n9. Используй только материалы Minecraft 1.12.2: STONE, DIRT, GRASS, COBBLESTONE, WOOD, PLANKS, GLASS, BRICK, SMOOTH_BRICK, LOG, WOOL, QUARTZ_BLOCK, CONCRETE, STAINED_GLASS, GLOWSTONE, BOOKSHELF и т.д." +
                        "\n10. ВАЖНО: в ответ отправляй ТОЛЬКО json и ничего более " +
                        "\n\nВерни ТОЛЬКО JSON массив с описанием блоков, без пояснений и комментариев.",
                width, height, length, length-1, width, height, length
        );

        // Улучшенный пользовательский запрос
        String userPrompt = String.format(
                "Создай детальную трехмерную постройку в Minecraft: %s. " +
                        "Это должно быть ПОЛНОЦЕННОЕ ТРЕХМЕРНОЕ СТРОЕНИЕ с внутренним пространством (не просто плоская стена или каркас). " +
                        "Обязательно включи внешние стены по всему периметру, внутренние комнаты, окна, двери, крышу и пол. " +
                        "Используй разнообразные материалы для разных элементов постройки. " +
                        "Размеры области: %dx%dx%d. " +
                        "Верни только JSON-массив с блоками без комментариев.",
                prompt, width, height, length
        );

        // Формируем JSON для запроса с увеличенным max_tokens
        String jsonBody = "{" +
                "\"model\":\"local-model\"," +
                "\"messages\":[" +
                "{\"role\":\"system\",\"content\":\"" + escapeJson(systemPrompt) + "\"}," +
                "{\"role\":\"user\",\"content\":\"" + escapeJson(userPrompt) + "\"}" +
                "]," +
                "\"temperature\":0.7," +
                "\"max_tokens\":4000" +
                "}";

        // Отправляем запрос (код отправки остается прежним)
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // Читаем ответ
        int responseCode = conn.getResponseCode();
        StringBuilder response = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        responseCode >= 200 && responseCode < 300
                                ? conn.getInputStream()
                                : conn.getErrorStream(),
                        StandardCharsets.UTF_8))) {

            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
        }

        if (responseCode >= 300) {
            throw new Exception("API Error (" + responseCode + "): " + response.toString());
        }

        // Парсим ответ и извлекаем контент
        JSONParser parser = new JSONParser();
        JSONObject jsonResponse = (JSONObject) parser.parse(response.toString());

        JSONArray choices = (JSONArray) jsonResponse.get("choices");
        JSONObject firstChoice = (JSONObject) choices.get(0);
        JSONObject message = (JSONObject) firstChoice.get("message");
        String content = (String) message.get("content");

        // Улучшенное извлечение JSON
        return extractCompletedJson(content);
    }
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    private String extractCompletedJson(String content) {
        // Удаляем переносы строк для упрощения работы
        String cleanContent = content.replace("\n", "").replace("\r", "");

        // Ищем начало и конец JSON-массива
        int start = cleanContent.indexOf('[');
        if (start == -1) {
            // Если не нашли открывающую скобку, используем шаблонный ответ
            return createSimpleHouseJson();
        }

        int end = findMatchingClosingBracket(cleanContent, start);
        if (end == -1) {
            // Если не нашли закрывающую скобку, пробуем восстановить JSON
            return fixIncompleteJson(cleanContent.substring(start));
        }

        // Если нашли полный массив, возвращаем его
        return cleanContent.substring(start, end + 1);
    }

    private int findMatchingClosingBracket(String text, int openPos) {
        int depth = 1;
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = openPos + 1; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"' && !escaped) {
                inQuotes = !inQuotes;
                continue;
            }

            if (inQuotes) continue;

            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private String fixIncompleteJson(String partialJson) {
        // Находим последний полный объект
        int lastCompleteObject = -1;
        int depth = 0;
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < partialJson.length(); i++) {
            char c = partialJson.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"' && !escaped) {
                inQuotes = !inQuotes;
                continue;
            }

            if (!inQuotes) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        lastCompleteObject = i;
                    }
                }
            }
        }

        if (lastCompleteObject != -1) {
            // Вырезаем до последнего полного объекта и закрываем массив
            return partialJson.substring(0, lastCompleteObject + 1) + "]";
        }

        // Если не нашли полных объектов, возвращаем шаблонное здание
        return createSimpleHouseJson();
    }

    // Шаблон простого дома для аварийных случаев
    private String createSimpleHouseJson() {
        StringBuilder json = new StringBuilder();
        json.append("[");

        // Пол из камня
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                json.append(String.format(
                        "{\"x\":%d,\"y\":0,\"z\":%d,\"material\":\"STONE\"},",
                        x, z
                ));
            }
        }

        // Стены из дерева
        for (int y = 1; y < 5; y++) {
            for (int x = 0; x < 10; x++) {
                json.append(String.format(
                        "{\"x\":%d,\"y\":%d,\"z\":0,\"material\":\"WOOD\"},",
                        x, y
                ));
                json.append(String.format(
                        "{\"x\":%d,\"y\":%d,\"z\":9,\"material\":\"WOOD\"},",
                        x, y
                ));
            }

            for (int z = 1; z < 9; z++) {
                json.append(String.format(
                        "{\"x\":0,\"y\":%d,\"z\":%d,\"material\":\"WOOD\"},",
                        y, z
                ));
                json.append(String.format(
                        "{\"x\":9,\"y\":%d,\"z\":%d,\"material\":\"WOOD\"},",
                        y, z
                ));
            }
        }

        // Крыша
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                json.append(String.format(
                        "{\"x\":%d,\"y\":5,\"z\":%d,\"material\":\"WOOD\"},",
                        x, z
                ));
            }
        }

        // Окна
        json.append("{\"x\":2,\"y\":2,\"z\":0,\"material\":\"GLASS\"},");
        json.append("{\"x\":3,\"y\":2,\"z\":0,\"material\":\"GLASS\"},");
        json.append("{\"x\":6,\"y\":2,\"z\":0,\"material\":\"GLASS\"},");
        json.append("{\"x\":7,\"y\":2,\"z\":0,\"material\":\"GLASS\"},");

        json.append("{\"x\":2,\"y\":2,\"z\":9,\"material\":\"GLASS\"},");
        json.append("{\"x\":3,\"y\":2,\"z\":9,\"material\":\"GLASS\"},");
        json.append("{\"x\":6,\"y\":2,\"z\":9,\"material\":\"GLASS\"},");
        json.append("{\"x\":7,\"y\":2,\"z\":9,\"material\":\"GLASS\"},");

        // Дверь
        json.append("{\"x\":4,\"y\":1,\"z\":0,\"material\":\"AIR\"},");
        json.append("{\"x\":5,\"y\":1,\"z\":0,\"material\":\"AIR\"},");
        json.append("{\"x\":4,\"y\":2,\"z\":0,\"material\":\"AIR\"},");
        json.append("{\"x\":5,\"y\":2,\"z\":0,\"material\":\"AIR\"}");

        json.append("]");
        return json.toString();
    }

    private void buildStructure(String structureData, Location minPoint, String worldName, Player player) throws Exception {
        JSONParser parser = new JSONParser();
        JSONArray blocksArray;

        try {
            blocksArray = (JSONArray) parser.parse(structureData);
        } catch (ParseException e) {
            getLogger().warning("Ошибка парсинга JSON: " + e.getMessage());
            getLogger().warning("Структура данных: " + structureData.substring(0, Math.min(100, structureData.length())));
            throw new Exception("Ошибка при парсинге JSON: " + e.getMessage());
        }

        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new Exception("Мир не найден!");
        }

        // Подсчет для статистики
        int blockCount = 0;
        int totalBlocks = blocksArray.size();
        int errorCount = 0;
        Set<Integer> zValues = new HashSet<>();

        player.sendMessage(String.format("§aНачинаем строительство! Всего блоков: §6%d", totalBlocks));

        for (Object obj : blocksArray) {
            if (!(obj instanceof JSONObject)) {
                errorCount++;
                continue;
            }

            JSONObject blockData = (JSONObject) obj;

            try {
                // Проверка наличия координат
                if (!blockData.containsKey("x") || !blockData.containsKey("y") || !blockData.containsKey("z")) {
                    getLogger().warning("Неполные данные блока: " + blockData);
                    errorCount++;
                    continue;
                }

                int x = ((Number) blockData.get("x")).intValue() + minPoint.getBlockX();
                int y = ((Number) blockData.get("y")).intValue() + minPoint.getBlockY();
                int z = ((Number) blockData.get("z")).intValue() + minPoint.getBlockZ();

                zValues.add(z); // Для статистики

                // Получаем имя материала - поддерживаем оба поля: "material" и "type"
                String materialName;
                if (blockData.containsKey("material")) {
                    materialName = ((String) blockData.get("material")).toUpperCase();
                } else if (blockData.containsKey("type")) {
                    materialName = ((String) blockData.get("type")).toUpperCase();
                } else if (blockData.containsKey("id")) {
                    materialName = ((String) blockData.get("id")).toUpperCase();
                } else {
                    getLogger().warning("Неполные данные блока (нет поля material, type или id): " + blockData);
                    errorCount++;
                    continue;
                }

                Material material = getMaterial(materialName);

                if (material == null) {
                    getLogger().warning("Материал не найден: " + materialName);
                    material = Material.STONE;
                }

                Block block = world.getBlockAt(x, y, z);
                block.setType(material);

                blockCount++;

                // Уведомляем о прогрессе каждые 10% построенных блоков
                if (totalBlocks >= 100 && blockCount % (totalBlocks / 10) == 0) {
                    int percentage = (blockCount * 100 / totalBlocks);
                    player.sendMessage(String.format("§aПостроено §b%d%% §a(%d/%d блоков)",
                            percentage, blockCount, totalBlocks));
                }
            } catch (Exception e) {
                errorCount++;
                if (errorCount <= 5) { // Показываем только первые 5 ошибок
                    getLogger().warning("Ошибка при установке блока: " + e.getMessage() + ", данные: " + blockData);
                }
            }
        }

        // Статистика использования Z-координат
        if (!zValues.isEmpty()) {
            player.sendMessage(String.format("§eГлубина постройки: §6%d§e уникальных значений Z", zValues.size()));
            if (zValues.size() <= 2) {
                player.sendMessage("§c⚠️ Предупреждение: Ваша постройка почти плоская. Для более объемных построек укажите в запросе '3D' или 'трехмерный'.");
            }
        }

        if (errorCount > 0) {
            player.sendMessage(String.format("§eПри строительстве возникло §c%d§e ошибок, но постройка завершена.", errorCount));
        }
    }

    private Material getMaterial(String name) {
        if (name == null) {
            getLogger().warning("Название материала null, используем STONE");
            return Material.STONE;
        }

        // Используем кеш для ускорения
        if (materialCache.containsKey(name)) {
            return materialCache.get(name);
        }

        // Пытаемся получить материал по имени
        Material material;
        try {
            material = Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            // Обрабатываем специальные случаи
            switch (name) {
                case "SLIME":
                    material = Material.SLIME_BLOCK;
                    break;
                case "AIR":
                    material = Material.AIR;
                    break;
                case "WATER":
                case "FLOWING_WATER":
                case "WATER_BLOCK":
                    material = Material.WATER;
                    break;
                case "LAVA":
                case "FLOWING_LAVA":
                case "LAVA_BLOCK":
                    material = Material.LAVA;
                    break;
                case "OAK_PLANKS":
                case "SPRUCE_PLANKS":
                case "BIRCH_PLANKS":
                case "JUNGLE_PLANKS":
                case "ACACIA_PLANKS":
                case "DARK_OAK_PLANKS":
                case "PLANKS":
                    material = Material.WOOD;
                    break;
                case "OAK_LOG":
                case "SPRUCE_LOG":
                case "BIRCH_LOG":
                case "JUNGLE_LOG":
                case "ACACIA_LOG":
                case "DARK_OAK_LOG":
                    material = Material.LOG;
                    break;
                case "WHITE_WOOL":
                case "ORANGE_WOOL":
                case "MAGENTA_WOOL":
                case "LIGHT_BLUE_WOOL":
                case "YELLOW_WOOL":
                case "LIME_WOOL":
                case "PINK_WOOL":
                case "GRAY_WOOL":
                case "LIGHT_GRAY_WOOL":
                case "CYAN_WOOL":
                case "PURPLE_WOOL":
                case "BLUE_WOOL":
                case "BROWN_WOOL":
                case "GREEN_WOOL":
                case "RED_WOOL":
                case "BLACK_WOOL":
                    material = Material.WOOL;
                    break;
                case "GRASS_BLOCK":
                    material = Material.GRASS;
                    break;
                case "COBBLESTONE_WALL":
                    material = Material.COBBLESTONE;
                    break;
                default:
                    // Пробуем найти близкое соответствие
                    if (name.endsWith("_CONCRETE") || name.endsWith("_CONCRETE_POWDER")) {
                        material = Material.CONCRETE;
                    } else if (name.contains("GLASS")) {
                        material = Material.GLASS;
                    } else if (name.contains("STONE")) {
                        material = Material.STONE;
                    } else if (name.contains("BRICK")) {
                        material = Material.BRICK;
                    } else if (name.contains("LOG") || name.contains("WOOD")) {
                        material = Material.LOG;
                    } else {
                        getLogger().warning("Неизвестный материал: " + name + ", заменяем на STONE");
                        material = Material.STONE;
                    }
            }
        }

        // Сохраняем в кеш и возвращаем
        materialCache.put(name, material);
        return material;
    }
}
