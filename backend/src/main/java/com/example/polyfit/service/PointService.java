package com.example.polyfit.service;

import com.example.polyfit.model.Point;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class PointService {

    private final List<Point> points = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        if (points.isEmpty()) {
            points.addAll(List.of(
                    new Point(-2, -4),
                    new Point(-1, -1),
                    new Point(0, 0),
                    new Point(1, 2),
                    new Point(2, 4)
            ));
        }
    }

    public List<Point> getAll() {
        return new ArrayList<>(points);
    }

    public void replaceAll(List<Point> newPoints) {
        points.clear();
        points.addAll(newPoints);
    }
}
