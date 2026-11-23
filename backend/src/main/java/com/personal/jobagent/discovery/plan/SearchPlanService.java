package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.common.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SearchPlanService {

    private static final Logger log = LoggerFactory.getLogger(SearchPlanService.class);

    private final SearchPlanRepository repository;
    private final SearchPlanQueryExpander queryExpander;

    public SearchPlanService(SearchPlanRepository repository, SearchPlanQueryExpander queryExpander) {
        this.repository = repository;
        this.queryExpander = queryExpander;
    }

    public List<SearchPlanRecord> listPlans(UUID profileId) {
        if (profileId == null) {
            throw new IllegalArgumentException("profileId cannot be null");
        }
        return repository.findByProfileId(profileId);
    }

    public Optional<SearchPlanRecord> getPlan(UUID id, UUID profileId) {
        if (id == null || profileId == null) {
            return Optional.empty();
        }
        return repository.findByIdAndProfileId(id, profileId);
    }

    @Transactional
    public SearchPlanRecord createPlan(UUID profileId, SearchPlanRecord request) {
        if (profileId == null) {
            throw new IllegalArgumentException("profileId cannot be null");
        }
        validatePlan(request);

        SearchPlanRecord recordToCreate = new SearchPlanRecord(
                UuidV7.generate(),
                profileId,
                request.name(),
                request.targetTitles(),
                request.queryFacets(),
                request.targetSources(),
                request.searchFrequency(),
                request.maxResultsPerRun(),
                request.active(),
                null,
                Instant.now(),
                Instant.now()
        );

        SearchPlanRecord created = repository.create(recordToCreate);
        log.info("Created search plan {} for profile {}", created.id(), profileId);
        return created;
    }

    @Transactional
    public Optional<SearchPlanRecord> updatePlan(UUID id, UUID profileId, SearchPlanRecord request) {
        if (id == null || profileId == null) {
            return Optional.empty();
        }
        validatePlan(request);

        Optional<SearchPlanRecord> existing = repository.findByIdAndProfileId(id, profileId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        SearchPlanRecord updatedRecord = new SearchPlanRecord(
                id,
                profileId,
                request.name(),
                request.targetTitles(),
                request.queryFacets(),
                request.targetSources(),
                request.searchFrequency(),
                request.maxResultsPerRun(),
                request.active(),
                existing.get().lastRunAt(),
                existing.get().createdAt(),
                Instant.now()
        );

        boolean ok = repository.update(id, updatedRecord);
        return ok ? repository.findByIdAndProfileId(id, profileId) : Optional.empty();
    }

    @Transactional
    public boolean deletePlan(UUID id, UUID profileId) {
        if (id == null || profileId == null) {
            return false;
        }
        boolean deleted = repository.delete(id, profileId);
        if (deleted) {
            log.info("Deleted search plan {} for profile {}", id, profileId);
        }
        return deleted;
    }

    public Optional<SearchPlanQueryExpander.ExpandedQueryPlan> expandPlan(UUID id, UUID profileId) {
        return getPlan(id, profileId).map(queryExpander::expand);
    }

    public boolean isExecutionLeaseActive(UUID planId) {
        return repository.isExecutionLeaseActive(planId);
    }

    private void validatePlan(SearchPlanRecord plan) {
        if (plan.name() == null || plan.name().trim().isEmpty()) {
            throw new IllegalArgumentException("Search plan name cannot be blank");
        }
        if (plan.name().trim().length() > 120) {
            throw new IllegalArgumentException("Search plan name cannot exceed 120 characters");
        }
        if (plan.maxResultsPerRun() < 1 || plan.maxResultsPerRun() > 500) {
            throw new IllegalArgumentException("maxResultsPerRun must be between 1 and 500");
        }
        String freq = plan.searchFrequency();
        if (freq != null && !List.of("HOURLY", "DAILY", "WEEKLY", "MANUAL").contains(freq.toUpperCase())) {
            throw new IllegalArgumentException("Invalid search frequency: " + freq);
        }
    }
}
