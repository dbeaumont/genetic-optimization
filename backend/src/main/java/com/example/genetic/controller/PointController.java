package com.example.genetic.controller;

import com.example.genetic.model.Point;
import com.example.genetic.service.PointService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/points")
@CrossOrigin
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    @GetMapping
    public List<Point> getPoints() {
        return pointService.getAll();
    }

    @PutMapping
    public List<Point> updatePoints(@RequestBody @Valid @NotEmpty List<Point> points) {
        pointService.replaceAll(points);
        return pointService.getAll();
    }
}
