package org.tyler.dao.foodrecord;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.model.food.Food;
import org.tyler.model.food.FoodRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * File-based persistence for food records.
 *
 * <p>Serializes JSON and delegates storage to {@link IFileSandboxRead}/{@link IFileSandboxWrite},
 * following {@link org.tyler.service.userInfo.UserInfoService} without accessing disk directly.
 *
 * <p>This legacy implementation stores records in {@code food-records.json}.
 * SQLite is available through the same {@link IFoodRecordDAO} contract.
 */
@Repository
public class FoodRecordDAO implements IFoodRecordDAO {

    private static final Logger log = LoggerFactory.getLogger(FoodRecordDAO.class);

    // Reuse a thread-safe ObjectMapper for JSON serialization;
    // FileSandbox performs the actual reads and writes.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final String relativePath;

    public FoodRecordDAO(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${food.file-path:food-records.json}") String relativePath) {
        this.reader = reader;
        this.writer = writer;
        // A relative path within the FileSandbox root.
        this.relativePath = relativePath;
    }

    @Override
    public List<Food> load() {
        // Check existence before reading to avoid FileReadException for a missing file.
        if (!reader.exists(relativePath)) {
            return List.of();
        }
        try {
            List<Food> foods = objectMapper.readValue(
                    reader.read(relativePath),
                    new TypeReference<List<Food>>() {});
            log.debug("Loaded food records from {}", relativePath);
            return foods == null ? List.of() : foods;
        } catch (Exception e) {
            // Return an empty list if the file is unreadable or contains invalid JSON.
            log.warn("Failed to read food records; returning an empty list: {}", relativePath, e);
            return List.of();
        }
    }

    @Override
    public void save(List<Food> foods) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(foods);
            writer.write(relativePath, json);
            log.debug("Saved {} food records to {}", foods == null ? 0 : foods.size(), relativePath);
        } catch (IOException | FileWriteException e) {
            log.error("Failed to write food records: {}", relativePath, e);
            // GenericExceptionHandler maps IllegalStateException to HTTP 500.
            throw new IllegalStateException("Failed to save food records. Please try again later", e);
        }
    }

    @Override
    public List<Food> loadByDate(String date) {
        requireDate(date);
        List<Food> result = new ArrayList<>();
        for (Food food : load()) {
            if (food != null && date.equals(dateOf(food))) {
                result.add(food);
            }
        }
        return result;
    }

    @Override
    public void saveByDate(String date, Food food) {
        requireDate(date);
        if (food == null) {
            throw new IllegalArgumentException("Food must not be null");
        }
        // Append to the existing list so multiple meals on the same day are retained.
        List<Food> foods = new ArrayList<>(load());
        foods.add(food);
        save(foods);
    }

    @Override
    public List<FoodRecord> loadRecordsByDate(String date) {
        throw new UnsupportedOperationException("The JSON DAO does not support records with IDs");
    }

    @Override
    public boolean deleteByDate(String date) {
        throw new UnsupportedOperationException("The JSON DAO does not support deletion by date");
    }

    @Override
    public boolean deleteById(long id) {
        throw new UnsupportedOperationException("The JSON DAO does not support deletion by ID");
    }

    private static String dateOf(Food food) {
        return food.genericInfo() == null ? null : food.genericInfo().date();
    }

    private static void requireDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("Date must not be blank");
        }
    }
}