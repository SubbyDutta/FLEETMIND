package com;

import com.model.Driver;
import fleetmind.events.DriverState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DriverRegistry {
    private final Map<String, Driver> drivers= new ConcurrentHashMap<>();
    @PostConstruct
    public void initializeDrivers(){
        addDriver("driver-1", "John", 22.5726, 88.3639);
        addDriver("driver-2", "Alex", 22.5750, 88.3675);
        addDriver("driver-3", "Mike", 22.5698, 88.3601);
        addDriver("driver-4", "David", 22.5782, 88.3708);
        addDriver("driver-5", "Chris", 22.5712, 88.3656);
        addDriver("driver-6",  "Sam",   22.5601, 88.3590);
        addDriver("driver-7",  "Tom",   22.5660, 88.3720);
        addDriver("driver-8",  "Raj",   22.5805, 88.3550);
        addDriver("driver-9",  "Leo",   22.5550, 88.3680);
        addDriver("driver-10", "Max",   22.5740, 88.3520);
        addDriver("driver-11", "Eve",   22.5690, 88.3750);
        addDriver("driver-12", "Zoe",   22.5620, 88.3635);
        addDriver("driver-13", "Ravi",   22.5480, 88.3560);
        addDriver("driver-14", "Nina",   22.5330, 88.3620);
        addDriver("driver-15", "Omar",   22.5590, 88.3450);
        addDriver("driver-16", "Isha",   22.5250, 88.3690);
        addDriver("driver-17", "Kian",   22.5410, 88.3500);
        addDriver("driver-18", "Tara",   22.5860, 88.3640);
        addDriver("driver-19", "Arjun",  22.5930, 88.3700);
        addDriver("driver-20", "Meera",  22.5200, 88.3580);
        addDriver("driver-21", "Dev",    22.5300, 88.3460);
        addDriver("driver-22", "Fatima", 22.5570, 88.3760);
        addDriver("driver-23", "Rohan",  22.5640, 88.3830);
        addDriver("driver-24", "Lily",   22.5450, 88.3780);
        addDriver("driver-25", "Vik",    22.5760, 88.3600);
        addDriver("driver-26", "Anya",   22.5510, 88.3650);
        addDriver("driver-27", "Kabir",  22.5380, 88.3730);

    }
    public void addDriver(String id,String name,double latitude,double longitude)
    {
        Driver driver = Driver.builder()
                .id(id)
                .name(name)
                .currentLatitude(latitude)
                .currentLongitude(longitude)
                .targetLatitude(latitude)
                .targetLongitude(longitude)
                .status(DriverState.IDLE)
                .stuck(false)
                .build();
        drivers.put(id,driver);
    }
    public Collection<Driver> getALLDrivers()
    {
        return drivers.values();
    }
    public Driver getDriver(String driverId) {
        return drivers.get(driverId);
    }
    public void saveDriver(Driver driver) {
        drivers.put(driver.getId(), driver);
    }

    public boolean contains(String driverId) {
        return drivers.containsKey(driverId);
    }




}
