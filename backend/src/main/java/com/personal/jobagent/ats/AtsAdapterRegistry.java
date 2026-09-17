package com.personal.jobagent.ats;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AtsAdapterRegistry {

    private final List<AtsAdapter> adapters;

    public AtsAdapterRegistry(List<AtsAdapter> adapters) {
        this.adapters = adapters;
    }

    public List<AtsAdapter> getAdapters() {
        return adapters;
    }

    public Optional<AtsAdapter> findAdapterForUrl(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        return adapters.stream()
                .filter(a -> a.matchesUrl(url))
                .findFirst();
    }

    public Optional<AtsAdapter> findByKind(AtsKind kind) {
        return adapters.stream()
                .filter(a -> a.kind() == kind)
                .findFirst();
    }
}