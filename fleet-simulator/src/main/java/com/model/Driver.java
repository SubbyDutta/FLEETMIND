package com.model;

import fleetmind.events.DriverState;
import fleetmind.events.OrderEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Driver {
 private String id;
 private String name;
 private double currentLatitude;
 private double currentLongitude;
 private double targetLatitude;
 private double targetLongitude;
 private DriverState status;
 private OrderEvent currentOrder;
 private boolean stuck;
 // the road route for the current leg (waypoints as {lng, lat}) and how far along it we are
 private List<double[]> route;
 private int routeIndex;

}
