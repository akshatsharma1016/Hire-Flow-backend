package com.kartgen.hireflow.jobs;

import com.kartgen.hireflow.profile.Skill;
import com.kartgen.hireflow.profile.SkillAlias;
import com.kartgen.hireflow.profile.SkillAliasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class JDAnalyzer {

    private final SkillAliasRepository aliasRepo;

    public record AnalyzedJD(
            List<UUID> mustSkills,
            List<UUID> preferredSkills,
            Integer minExperienceMonths,
            Integer maxExperienceMonths
    ) {}

    private static final Pattern EXP_RANGE_RE = Pattern.compile(
            "(\\d{1,2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_EXP_RE = Pattern.compile(
            "(?:at\\s+least|minimum(?:\\s+of)?|min\\.?)?\\s*(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)" +
                    "(?:\\s+of)?(?:\\s+[\\w&/+\\-]+){0,3}\\s+(?:experience|exp)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERRED_HEADING_RE = Pattern.compile(
            "(?im)^\\s*(?:preferred(?:\\s+skills?)?|nice\\s+to\\s+have|good\\s+to\\s+have|bonus(?:\\s+skills?)?)\\b.*$");

    public AnalyzedJD analyze(String jdText, List<Skill> allSkills) {
        if (jdText == null || jdText.isBlank()) {
            return new AnalyzedJD(List.of(), List.of(), null, null);
        }

        int preferredIdx = -1;
        Matcher heading = PREFERRED_HEADING_RE.matcher(jdText);
        if (heading.find()) {
            preferredIdx = heading.start();
        }

        String mustSectionRaw = preferredIdx > 0 ? jdText.substring(0, preferredIdx) : jdText;
        String preferredSectionRaw = preferredIdx > 0 ? jdText.substring(preferredIdx) : "";

        // Normalize document sections once before the loop (performance optimization)
        String mustSection = mustSectionRaw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#.\\s]", " ")
                .replaceAll("\\s+", " ");
        String preferredSection = preferredSectionRaw.isBlank() ? "" :
                preferredSectionRaw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#.\\s]", " ")
                .replaceAll("\\s+", " ");

        Map<UUID, String> namesById = new HashMap<>();
        for (Skill s : allSkills) namesById.put(s.getId(), s.getCanonicalName());
        Map<UUID, List<String>> aliasesById = new HashMap<>();
        for (SkillAlias alias : aliasRepo.findAll()) {
            if (namesById.containsKey(alias.getSkillId())) {
                aliasesById.computeIfAbsent(alias.getSkillId(), k -> new ArrayList<>())
                        .add(alias.getAliasNormalized());
            }
        }

        List<UUID> mustSkills = new ArrayList<>();
        List<UUID> preferredSkills = new ArrayList<>();
        for (Skill skill : allSkills) {
            List<String> terms = new ArrayList<>();
            terms.add(skill.getCanonicalName());
            terms.addAll(aliasesById.getOrDefault(skill.getId(), List.of()));
            terms.sort(Comparator.comparingInt(String::length).reversed());

            boolean inMust = containsAnyTerm(mustSection, terms);
            boolean inPreferred = !preferredSection.isBlank() && containsAnyTerm(preferredSection, terms);
            if (inMust) {
                mustSkills.add(skill.getId());
            } else if (inPreferred) {
                preferredSkills.add(skill.getId());
            }
        }

        Integer minExp = null;
        Integer maxExp = null;
        Matcher rangeMatcher = EXP_RANGE_RE.matcher(jdText);
        if (rangeMatcher.find()) {
            int lo = Integer.parseInt(rangeMatcher.group(1));
            int hi = Integer.parseInt(rangeMatcher.group(2));
            if (lo > 0 && hi >= lo && hi <= 50) {
                minExp = lo * 12;
                maxExp = hi * 12;
            }
        }
        if (minExp == null) {
            Matcher minMatcher = MIN_EXP_RE.matcher(jdText);
            if (minMatcher.find()) {
                int years = Integer.parseInt(minMatcher.group(1));
                if (years > 0 && years <= 50) minExp = years * 12;
            }
        }

        log.debug("JD analyzed: {} must-skills, {} preferred-skills, exp {}-{} months",
                mustSkills.size(), preferredSkills.size(), minExp, maxExp);
        return new AnalyzedJD(mustSkills, preferredSkills, minExp, maxExp);
    }

    private boolean containsAnyTerm(String normalizedSection, List<String> terms) {
        if (normalizedSection == null || normalizedSection.isBlank()) return false;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            String normalizedTerm = term.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9+#.\\s]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (normalizedTerm.isBlank()) continue;
            Pattern p = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalizedTerm) + "(?![a-z0-9])");
            if (p.matcher(normalizedSection).find()) return true;
        }
        return false;
    }
}
