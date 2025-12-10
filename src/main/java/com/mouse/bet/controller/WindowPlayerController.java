package com.mouse.bet.controller;

import com.mouse.bet.manager.WindowSyncManager;
import com.mouse.bet.window.MSportWindow;
import com.mouse.bet.window.SportyWindow;
import com.mouse.bet.window.WindowPlayer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for controlling betting windows
 */
@Slf4j
@RestController
@RequestMapping("/api/windows")
@RequiredArgsConstructor
public class WindowPlayerController {

    private final WindowPlayer windowPlayer;
    private final MSportWindow mSportWindow;
    private final SportyWindow sportyWindow;
    private final WindowSyncManager syncManager;

    /**
     * Start both windows
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startWindows() {
        try {
            windowPlayer.startWindows();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Both windows started successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to start windows: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Stop both windows
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopWindows() {
        try {
            windowPlayer.stopWindows();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Both windows stopped successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to stop windows: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Restart both windows
     */
    @PostMapping("/restart")
    public ResponseEntity<Map<String, Object>> restartWindows() {
        try {
            windowPlayer.restartWindows();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Both windows restarted successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to restart windows: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Pause both windows
     */
    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pauseWindows() {
        try {
            windowPlayer.pauseWindows();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Both windows paused"
            ));
        } catch (Exception e) {
            log.error("Failed to pause windows: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Resume both windows
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resumeWindows() {
        try {
            windowPlayer.resumeWindows();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Both windows resumed"
            ));
        } catch (Exception e) {
            log.error("Failed to resume windows: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get overall status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();

        try {
            WindowPlayer.PlayerStatus playerStatus = windowPlayer.getStatus();

            response.put("player", Map.of(
                    "isRunning", playerStatus.isRunning(),
                    "isShuttingDown", playerStatus.isShuttingDown(),
                    "activeWindows", playerStatus.getActiveWindows(),
                    "mSportActive", playerStatus.isMSportWindowActive(),
                    "sportyActive", playerStatus.isSportyWindowActive()
            ));

            response.put("msport", Map.of(
                    "isRunning", mSportWindow.isRunning(),
                    "isPaused", mSportWindow.isPaused(),
                    "queueSize", mSportWindow.getTaskQueue().size()
            ));

            response.put("sporty", Map.of(
                    "isRunning", sportyWindow.isRunning(),
                    "isPaused", sportyWindow.isPaused(),
                    "queueSize", sportyWindow.getTaskQueue().size()
            ));

            response.put("sync", Map.of(
                    "registeredWindows", playerStatus.getSyncManagerRegistered(),
                    "activeCoordinations", playerStatus.getActiveCoordinations()
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to get status: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Pause specific window
     */
    @PostMapping("/pause/{bookmaker}")
    public ResponseEntity<Map<String, Object>> pauseWindow(@PathVariable String bookmaker) {
        try {
            if ("msport".equalsIgnoreCase(bookmaker)) {
                mSportWindow.pause();
            } else if ("sporty".equalsIgnoreCase(bookmaker)) {
                sportyWindow.pause();
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Invalid bookmaker. Use 'msport' or 'sporty'"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", bookmaker + " window paused"
            ));
        } catch (Exception e) {
            log.error("Failed to pause {}: {}", bookmaker, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Resume specific window
     */
    @PostMapping("/resume/{bookmaker}")
    public ResponseEntity<Map<String, Object>> resumeWindow(@PathVariable String bookmaker) {
        try {
            if ("msport".equalsIgnoreCase(bookmaker)) {
                mSportWindow.resume();
            } else if ("sporty".equalsIgnoreCase(bookmaker)) {
                sportyWindow.resume();
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Invalid bookmaker. Use 'msport' or 'sporty'"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", bookmaker + " window resumed"
            ));
        } catch (Exception e) {
            log.error("Failed to resume {}: {}", bookmaker, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Clear sync manager state
     */
    @PostMapping("/sync/clear")
    public ResponseEntity<Map<String, Object>> clearSyncState() {
        try {
            syncManager.clearAll();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Sync state cleared"
            ));
        } catch (Exception e) {
            log.error("Failed to clear sync state: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }
}