package com;

import com.model.Driver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sim")
@CrossOrigin(origins = "*")  // ops console (:5173) triggers incidents directly
@RequiredArgsConstructor
public class IncidentController {

    private final DriverRegistry driverRegistry;


    @PostMapping("/incident/{driverId}")
    public ResponseEntity<String> freeze(@PathVariable String driverId) {
        Driver driver = driverRegistry.getDriver(driverId);
        if (driver == null) {
            return ResponseEntity.notFound().build();
        }
        driver.setStuck(true);
        return ResponseEntity.ok("Driver " + driverId + " is now STUCK");
    }


    @PostMapping("/recover/{driverId}")
    public ResponseEntity<String> recover(@PathVariable String driverId) {
        Driver driver = driverRegistry.getDriver(driverId);
        if (driver == null) {
            return ResponseEntity.notFound().build();
        }
        driver.setStuck(false);
        return ResponseEntity.ok("Driver " + driverId + " recovered");
    }
}
