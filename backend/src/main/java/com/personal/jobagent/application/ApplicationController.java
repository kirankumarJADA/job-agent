package com.personal.jobagent.application;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {
    private final ApplicationStatusService service;
    public ApplicationController(ApplicationStatusService service){this.service=service;}
    public record TransitionRequest(String targetStatus,String eventKey,String actor,Map<String,Object> context){}
    @GetMapping("/{id}") public ResponseEntity<?> get(@PathVariable UUID id){return service.find(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());}
    @GetMapping("/{id}/timeline") public ResponseEntity<?> timeline(@PathVariable UUID id){if(service.find(id).isEmpty())return ResponseEntity.notFound().build();return ResponseEntity.ok(Map.of("items",service.timeline(id)));}
    @PostMapping("/{id}/status") public ResponseEntity<?> transition(@PathVariable UUID id,@RequestBody TransitionRequest r){try{return ResponseEntity.ok(service.apply(id,r.targetStatus(),r.eventKey(),r.actor(),r.context()));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}catch(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("error",e.getMessage()));}}
}
