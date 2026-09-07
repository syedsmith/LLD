package lld.parkinglot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

enum VehicleType { CAR, BIKE, TRUCK }
enum ParkingSize { SMALL, MEDIUM, LARGE }
enum GateType { ENTRY, EXIT }

class Ticket{
    public static AtomicInteger counter = new AtomicInteger(0);
    Integer id;
    String spotId;
    String licensePlate;
    Instant entryTime;

    Ticket(String spotId, String licensePlate) {
        this.id = counter.incrementAndGet();
        this.spotId = spotId;
        this.licensePlate = licensePlate;
        this.entryTime = Instant.now(); // UTC timeline by default, to change to other zone need zoneId
        System.out.println("[TICKET] Created id=" + id + ", spot=" + spotId + ", vehicle=" + licensePlate);
    }
}

class Vehicle {
    String licensePlate;
    VehicleType vehicleType;
    Ticket ticket;
    Vehicle(String licensePlate, VehicleType vehicleType) {
        this.licensePlate = licensePlate;
        this.vehicleType = vehicleType;
    }
}

class Spot{
    AtomicInteger counter = new AtomicInteger(1);
    Integer id;
    String name;
    ParkingSize parkingSize;
    Vehicle vehicle;
    Integer floorNumer;

    Spot(ParkingSize parkingSize, String name, Integer floorNumer) {
        this.id = counter.getAndIncrement();
        this.name = name;
        this.parkingSize = parkingSize;
        this.vehicle = null;
        this.floorNumer = floorNumer;
    }
    public Integer getFloorNumer() {
        return floorNumer;
    }
    public Integer getId() {
        return id;
    }
}

class Floor{
    int floorNumber;
    List<Spot> spots = new ArrayList<>();
    Floor(int floorNumber){
        this.floorNumber = floorNumber;
    }
    public void addSpot(Spot spot) {
        spots.add(spot);
        System.out.println("[SPOT] available: " + spot.name + " / " + spot.parkingSize + ", count=" + spots.size());
    }

    public List<Spot> getSpots() {
        return spots;
    }
}

interface GateLogic{
    public void processVehicle(Vehicle vehicle);
}

class EntryGateLogic implements GateLogic {

    TicketManager ticketManager = new TicketManager();
    GateActionListener actionListener;

    EntryGateLogic(GateActionListener actionListener){
        this.actionListener = actionListener;
    }

    @Override
    public void processVehicle(Vehicle vehicle) {
        System.out.println("[ENTRY] Processing " + vehicle.licensePlate + " on " + Thread.currentThread().getName());
        Spot spot = actionListener.findSpot(vehicle);
        spot.vehicle = vehicle;
        System.out.println("[ENTRY] Spot selected=" + spot.name + " for " + vehicle.licensePlate);
        vehicle.ticket = ticketManager.createTicket(spot.name, vehicle.licensePlate);
        actionListener.addVehicleSpotRegistry(vehicle, spot);
        System.out.println("[ENTRY] Completed parking for " + vehicle.licensePlate);
    }
}

class ExitGateLogic implements GateLogic {
    TicketManager ticketManager = new TicketManager();
    GateActionListener actionListener;

    ExitGateLogic(GateActionListener actionListener){
        this.actionListener = actionListener;
    }

    @Override
    public void processVehicle(Vehicle vehicle) {
        System.out.println("[EXIT] Processing " + vehicle.licensePlate + " on " + Thread.currentThread().getName());
        Spot spot = actionListener.getVehicleSpotRegistry(vehicle);
        System.out.println("[EXIT] Found spot=" + (spot == null ? "null" : spot.name));
        actionListener.removeVehicleSpotRegistry(vehicle);
        spot.vehicle = null;
        Ticket ticket = vehicle.ticket;
        BigDecimal fee = ticketManager.calculateFee(ticket);
        System.out.println("[EXIT] Fee=" + fee + " for " + vehicle.licensePlate);
        actionListener.addAvailableSpot(spot);
        System.out.println("[EXIT] Released spot=" + spot.name);
    }
}
interface FeeCalculator{
    BigDecimal calculateFee(Ticket ticket);
}
class TimeFee implements FeeCalculator{
    @Override
    public BigDecimal calculateFee(Ticket ticket) {
        long timeDiff = Duration.between(ticket.entryTime, Instant.now()).toSeconds();
        return BigDecimal.valueOf(timeDiff);
    }
}
class BucketFee implements FeeCalculator{
    @Override
    public BigDecimal calculateFee(Ticket ticket) {
        long bucket = 2L;
        long timeDiff = Duration.between(ticket.entryTime, Instant.now()).toMillis();
        return BigDecimal.valueOf(timeDiff/bucket);
    }
}

class TicketManager{
    List<Ticket> tickets = new LinkedList<>();
    FeeCalculator feeCalculator = new TimeFee();

    public Ticket createTicket(String spotId, String licensePlate) {
        System.out.println("[TICKET-MANAGER] Creating ticket for " + licensePlate);
        Ticket ticket = new Ticket(spotId, licensePlate);
        tickets.add(ticket);
        return ticket;
    }

    public BigDecimal calculateFee(Ticket ticket) {
        return feeCalculator.calculateFee(ticket);
    }

    public boolean removeTicket(Ticket ticket) {
        return tickets.remove(ticket);
    }
}

class GateLogicFactory{
    public GateLogic getEntryGateLogic(GateActionListener actionListener){
        return new EntryGateLogic(actionListener);
    }
    public GateLogic getExitGateLogic(GateActionListener actionListener){
        return new ExitGateLogic(actionListener);
    }
}

interface GateActionListener {
    Spot findSpot(Vehicle vehicle);
    void addVehicleSpotRegistry(Vehicle vehicle, Spot spot);
    void removeVehicleSpotRegistry(Vehicle vehicle);
    Spot getVehicleSpotRegistry(Vehicle vehicle);
    void addAvailableSpot(Spot spot);


}

class Gate implements Runnable {
    int gateNumber;
    BlockingQueue<Vehicle> queue = new LinkedBlockingQueue<>();
    GateLogicFactory gateLogicFactory = new GateLogicFactory();
    GateLogic gateLogic;
    GateType gateType;
    GateActionListener gateActionListerner;

    Thread gateThread;
    AtomicBoolean isThreadStarted = new AtomicBoolean(false);

    public void start(){
        if(!isThreadStarted.compareAndSet(false,true)){
            System.out.println("[GATE] Gate Already running " + gateNumber);
            return;
        }
        gateThread = new Thread(this, "Gate-" + gateNumber);
        gateThread.start();
    }

    public void shutdown(){
        gateThread.interrupt();
    }

    Gate(int gateNumber, GateType gateType, GateActionListener gateActionListerner) {
        this.gateNumber = gateNumber;
        this.gateType =  gateType;
        this.gateActionListerner = gateActionListerner;
        this.gateLogic = gateType == GateType.ENTRY? gateLogicFactory.getEntryGateLogic(gateActionListerner): gateLogicFactory.getExitGateLogic(gateActionListerner);
    }

    public Integer getQueueSize() {
        return queue.size();
    }

    public void assignGate(Vehicle vehicle) {
        System.out.println("[GATE-" + gateNumber + "] enqueue=" + vehicle.licensePlate + ", before=" + queue.size());
        queue.add(vehicle);
        System.out.println("[GATE-" + gateNumber + "] after=" + queue.size());
    }

    @Override
    public void run(){
        while(!Thread.currentThread().isInterrupted()){
            try{
                Vehicle vehicle = queue.take();
                System.out.println("[GATE-" + gateNumber + "] dequeue=" + vehicle.licensePlate + ", remaining=" + queue.size());
                gateLogic.processVehicle(vehicle);
            }
            catch(InterruptedException e){
                Thread.currentThread().interrupt();
                System.out.println("Facing error in runnable "+ e.toString());
            }
        }
    }
}

class ParkingLotController implements GateActionListener {
    List<Gate> entryGates = new ArrayList<>();
    List<Gate> exitGates = new ArrayList<>();
    List<Floor> floors = new ArrayList<>();
    Map<String, Spot> vehicleSpotRegistry = new HashMap<>();
    Map<ParkingSize, ConcurrentSkipListSet<Spot>> map = new HashMap<>();


    ParkingLotController(){
        for(ParkingSize size : ParkingSize.values()){
            map.put(size, new ConcurrentSkipListSet<>(Comparator.comparingInt(Spot::getFloorNumer).thenComparingInt(Spot::getId)));
        }
    }

    public void shutdown(){
        for(Gate gate : entryGates){
            gate.shutdown();
        }
        for(Gate gate : exitGates){
            gate.shutdown();
        }
    }


    @Override
    public void addVehicleSpotRegistry(Vehicle vehicle, Spot spot){
        vehicleSpotRegistry.put(vehicle.licensePlate, spot);
        System.out.println("[REGISTRY] " + vehicle.licensePlate + " -> " + spot.name);
    }
    @Override
    public Spot getVehicleSpotRegistry(Vehicle vehicle){
        Spot spot = vehicleSpotRegistry.get(vehicle.licensePlate);
        System.out.println("[REGISTRY] lookup " + vehicle.licensePlate + " -> " + (spot == null ? "null" : spot.name));
        return spot;
    }
    @Override
    public void removeVehicleSpotRegistry(Vehicle vehicle){
        vehicleSpotRegistry.remove(vehicle.licensePlate);
        System.out.println("[REGISTRY] removed " + vehicle.licensePlate);
    }

    private Gate findMinQueueGate(List<Gate> gates){
        Gate minQueueGate = gates.get(0);
        for(Gate gate : gates){
            System.out.println("[CONTROLLER] gate=" + gate.gateNumber + " queue=" + gate.getQueueSize());
            if(gate.getQueueSize()<minQueueGate.getQueueSize()){
                minQueueGate = gate;
            }
        }
        return minQueueGate;
    }

    public void parkVehicle(Vehicle vehicle){
        System.out.println("[CONTROLLER] park request=" + vehicle.licensePlate);
        Gate gate = findMinQueueGate(entryGates);
        System.out.println("[CONTROLLER] selected entry gate=" + gate.gateNumber);
        gate.assignGate(vehicle);
    }

    public void unparkVehicle(Vehicle vehicle){
        Spot spot = getVehicleSpotRegistry(vehicle);
        if(spot == null){
            System.out.println("[CONTROLLER] vehicle=" + vehicle.licensePlate+" is yet to be parked, retry after some time");
            return;
        }
        System.out.println("[CONTROLLER] unpark request=" + vehicle.licensePlate);
        Gate gate = findMinQueueGate(exitGates);
        System.out.println("[CONTROLLER] selected exit gate=" + gate.gateNumber);
        gate.assignGate(vehicle);
    }

    @Override
    public void addAvailableSpot(Spot spot){
        spot.vehicle = null;
        ConcurrentSkipListSet<Spot> spots = map.get(spot.parkingSize);
        spots.add(spot);
    }

    public void addGate(Gate gate){
        if(gate.gateType == GateType.ENTRY){
            entryGates.add(gate);
        }
        else if(gate.gateType == GateType.EXIT){
            exitGates.add(gate);
        }
    }

    public boolean isParkingSizeCompatible(ParkingSize parkingSize, VehicleType vehicleType){
        if(parkingSize==ParkingSize.SMALL && vehicleType==VehicleType.BIKE){
            return true;
        }
        if(parkingSize==ParkingSize.MEDIUM && vehicleType==VehicleType.CAR){
            return true;
        }
        if(parkingSize==ParkingSize.LARGE && vehicleType==VehicleType.TRUCK){
            return true;
        }
        return false;
    }

    public Spot findSpot(Vehicle vehicle){

        for(ParkingSize pSize: map.keySet()){
            if(isParkingSizeCompatible(pSize, vehicle.vehicleType)){
                System.out.println("[SPOT] Checking " + pSize + ", available=" + map.get(pSize).size());
                Spot spot = map.get(pSize).pollFirst();
                System.out.println("[SPOT] Claimed=" + (spot == null ? "null" : spot.name));
                return spot;
            }
        }
        System.out.println("No spots available for "+ vehicle.vehicleType.toString());
        return null;
    }

    public void build(){
        for(int i=0; i<2; i++){
            Floor floor = new Floor(i+1);
            int k=1;
            for(int j=0; j<2; j++){
                Spot spot1 = new Spot(ParkingSize.SMALL, i+"-"+ k++, floor.floorNumber);
                Spot spot2 = new Spot(ParkingSize.LARGE, i+"-"+ k++, floor.floorNumber);
                Spot spot3 = new Spot(ParkingSize.MEDIUM, i+"-"+ k++, floor.floorNumber);
                addAvailableSpot(spot1);
                addAvailableSpot(spot2);
                addAvailableSpot(spot3);
                floor.addSpot(spot1);
                floor.addSpot(spot2);
                floor.addSpot(spot3);
            }
            floors.add(floor);
        }

        Gate gate1 = new Gate(1, GateType.ENTRY,  this);
        Gate gate2 = new Gate(2, GateType.ENTRY,  this);
        Gate gate3 = new Gate(3, GateType.EXIT,  this);
        Gate gate4 = new Gate(4, GateType.EXIT,  this);
        addGate(gate1);
        addGate(gate2);
        addGate(gate3);
        addGate(gate4);

        // REPLACED THREAD with LIFECYCLE MANAGEMENT
        gate1.start();
        gate2.start();
        gate3.start();
        gate4.start();
//        Thread  t1 = new Thread(gate1);
//        Thread t2 = new Thread(gate2);
//        Thread t3 = new Thread(gate3);
//        Thread t4 = new Thread(gate4);
//        t1.start();
//        t2.start();
//        t3.start();
//        t4.start();
    }

}

public class ParkingLotSystem {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[MAIN] start");
        ParkingLotController controller = new ParkingLotController();
        controller.build();

        Vehicle v1 = new Vehicle("abf13", VehicleType.CAR);
        Vehicle v2 = new Vehicle("678f13", VehicleType.BIKE);

        controller.parkVehicle(v1);
        controller.parkVehicle(v2);
        controller.unparkVehicle(v1);
        controller.unparkVehicle(v2);

        Thread.sleep(1000);
        controller.unparkVehicle(v1);
        controller.unparkVehicle(v2);
        System.out.println("[MAIN] requests submitted");


        Thread.sleep(10000);
        // THREAD with LIFECYCLE MANAGEMENT
        controller.shutdown();
    }
}
