package com.mouse.bet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.models.TeamAlias;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern; // Use Pattern for efficiency

@Slf4j
@Service
public class TeamAliasService {

    private static final String ALIASES_PATH_DEFAULT = "classpath:static/team_aliases.json";
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}");
    private static final Pattern NON_ALPHANUM_PATTERN = Pattern.compile("[^\\p{Alnum}]+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    @Value("${app.team-aliases.path:" + ALIASES_PATH_DEFAULT + "}")
    private String aliasesPath;
    private volatile Map<String, String> aliasToCanonicalMap = Map.of(); // Initialize to empty immutable map
    private volatile Set<String> allCanonicalNames = Set.of(); // Initialize to empty immutable set

    public TeamAliasService(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing TeamAliasService from {}", aliasesPath);
        try {
            List<TeamAlias> aliases = loadTeamAliases();
            rebuildMaps(aliases);
            log.info("TeamAliasService initialized: entries={}", aliasToCanonicalMap.size());
        } catch (IOException e) {
            log.error("Failed to initialize TeamAliasService from {}", aliasesPath, e);
            throw new RuntimeException("Failed to initialize TeamAliasService from " + aliasesPath, e);
        }
    }

    /** Reload at runtime. */
    public synchronized void reload() throws IOException {
        List<TeamAlias> aliases = loadTeamAliases();
        rebuildMaps(aliases);
        log.info("Reloaded alias maps: entries={}", aliasToCanonicalMap.size());
    }



    /** Returns Optional canonical name for any input variant. */
    private Optional<String> findCanonical(String maybeAlias) {
        if (maybeAlias == null || maybeAlias.isBlank()) return Optional.empty();
        String key = basicNormalize(maybeAlias);
        // Direct read on the volatile reference to the immutable map is safe and fast
        return Optional.ofNullable(aliasToCanonicalMap.get(key));
    }

    /** Returns canonical if known; otherwise returns the original input. */
    public String canonicalOrSelf(String maybeAlias) {
        return findCanonical(maybeAlias).orElse(maybeAlias);
    }

    /** True if the provided name equals a known canonical (case-sensitive). */
    public boolean isCanonical(String name) {
        // Direct read on the volatile reference to the immutable set is safe and fast
        return name != null && allCanonicalNames.contains(name);
    }

    public Map<String, String> aliasMapView() {

        return aliasToCanonicalMap;
    }
    public Set<String> canonicalNamesView()   {
        return allCanonicalNames;
    }

    private List<TeamAlias> loadTeamAliases() throws IOException {
        Resource resource = resourceLoader.getResource(aliasesPath);
        if (!resource.exists()) {
            throw new IOException("Team aliases file not found: " + aliasesPath);
        }
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<TeamAlias>>() {});
        }
    }

    private void rebuildMaps(List<TeamAlias> teamAliases) {

        Map<String, String> tmpMap = new HashMap<>(teamAliases.size() * 3); // Pre-size for efficiency
        Set<String> tmpCanon = new HashSet<>(teamAliases.size());

        for (TeamAlias alias : teamAliases) {
            if (alias.getName() == null) continue;

            String canonicalName = alias.getName();
            tmpCanon.add(canonicalName);

            // Map the canonical name itself (normalized)
            tmpMap.put(basicNormalize(canonicalName), canonicalName);

            // 2. Map all variants
            if (alias.getAliases() != null) {
                for (String variant : alias.getAliases()) {
                    if (variant == null) continue;
                    // If multiple aliases normalize to the same key, the last one wins.
                    // This is usually fine for mapping to a single canonical name.
                    tmpMap.put(basicNormalize(variant), canonicalName);
                }
            }
        }

        this.aliasToCanonicalMap = Map.copyOf(tmpMap);
        this.allCanonicalNames   = Set.copyOf(tmpCanon);

        log.info("Alias maps built/rebuilt. Aliases loaded: {}, Mappings created: {}",
                teamAliases.size(), this.aliasToCanonicalMap.size());
    }

    private String basicNormalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = DIACRITICS_PATTERN.matcher(n).replaceAll("");

        n = n.toLowerCase(Locale.ROOT);

        n = NON_ALPHANUM_PATTERN.matcher(n).replaceAll(" ");
        n = n.trim();
        n = WHITESPACE_PATTERN.matcher(n).replaceAll(" ");

        return n;
    }
}