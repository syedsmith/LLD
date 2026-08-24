package lld.elevator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

interface RequestAssigner {
    void elevatorReadyForNewRequests(Elevator elevator);
    void elevatorPicksDirectionAndRequest(Elevator elevator);
}

enum RequestStatus { WAITING, ASSIGNED, INSIDE, COMPLETED }
enum Direction { UP, DOWN, IDLE }


class PassengerRequest {
    int id; int source; int destination; String name; Integer requestTime; RequestStatus status = RequestStatus.WAITING;

    PassengerRequest(int id, int source, int destination, String name,  Integer requestTime) {
        this.id = id; this.source = source;
        this.destination = destination; this.name = name; this.requestTime = requestTime;
    }
}

class Elevator implements Runnable {

    int currentFloor;
    Direction direction;
    int maxFloor;
    int maxCapacity;
    int time = 0;
    List<PassengerRequest> passengers = new LinkedList<>();
    List<PassengerRequest> insidePassengers = new LinkedList<>();

    // Needed if distance is also calculated to onboard a passenger
    int acceptFloorDistance = 5;
    RequestAssigner requestAssigner;
    int id;


    Elevator(int id, RequestAssigner requestAssigner){
        this.id = id;
        this.requestAssigner = requestAssigner;
        this.direction = Direction.UP;
        this.maxFloor = 10;
        this.maxCapacity = 10;
        this.currentFloor = 0;
    }


    @Override
    public void run() {
        while (true) {
            processRequests();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void processRequests() {
        assignRequest();
        dropPassengers();
        pickPaasengers();
        elevatorDirectionChange();
        moveFloor();
        increaseTime();
    }

    private void assignRequest() {
        if(direction != Direction.IDLE){
            System.out.println("Elevator "+ id +" trying to pick passengers in the same direction.");
            requestAssigner.elevatorReadyForNewRequests(this);
        }
        else{
            System.out.println("Elevator "+ id +" trying to pick a passenger and direction.");
            requestAssigner.elevatorPicksDirectionAndRequest(this);
        }
    }

    private void elevatorDirectionChange() {
        if(passengers.size() == 0 && insidePassengers.size() == 0){
            System.out.println("No passengers to serve Elevator "+ id +" going IDLE in floor "+ currentFloor +" on time "+ time);
            direction = Direction.IDLE;
        }
        else{
            System.out.println("Assigned req size: "+ passengers.size() +" inside paasengers size: "+ insidePassengers.size());
        }
    }

    private void dropPassengers(){
        Iterator<PassengerRequest> iterator = insidePassengers.iterator();
        while(iterator.hasNext()){
            PassengerRequest insider = iterator.next();
            if(insider.destination==currentFloor){
                System.out.println("Dropping passenger "+ insider.name +" from elevator "+ id +" with destination "+ insider.destination +" in floor "+ currentFloor);
                iterator.remove();
            }
        }
    }

    private void pickPaasengers(){
        Iterator<PassengerRequest> iterator = passengers.iterator();
        while(iterator.hasNext()){
            PassengerRequest outsider = iterator.next();
            if(outsider.source==currentFloor){
                insidePassengers.add(outsider);
                System.out.println("Picking passenger "+ outsider.name +" into elevator "+id+" with source "+ outsider.source +" in floor "+ currentFloor);
                iterator.remove();
            }
        }
    }

    private void moveFloor() {
        if(direction!=Direction.IDLE){
            if(direction==Direction.UP){
                currentFloor++;
                System.out.println("Elevator moving UP to floor "+ currentFloor);
            }
            else {
                currentFloor--;
                System.out.println("Elevator moving DOWN to floor "+ currentFloor);
            }
        }
    }

    private void increaseTime() {
        this.time++;
    }

}

class ElevatorController implements RequestAssigner {

    List<Elevator> elevators;

    List<PassengerRequest> passengerRequests = new ArrayList<>();

    int maxRequestAssignment = 2;

    ReentrantLock lock = new ReentrantLock(true);

    ElevatorController(int noElevators) {
        elevators = new ArrayList<>();
        for (int i = 0; i < noElevators; i++) {
            Elevator elevator = new Elevator(i,this);
            elevators.add(elevator);
            new Thread(elevator).start();
        }
    }

    public boolean floorRequest(PassengerRequest passengerRequest) {
        passengerRequests.add(passengerRequest);
        return true;
    }

    private boolean assignRequest(PassengerRequest passengerRequest, Elevator elevator) {
        try{
            lock.lock();
            if(passengerRequest.status == RequestStatus.WAITING) {
                passengerRequest.status = RequestStatus.ASSIGNED;
                elevator.passengers.add(passengerRequest);
                System.out.println("Passenger "+ passengerRequest.name +" request has been assigned to elevator "+elevator.id);
                return true ;
            }
        }
        finally {
            lock.unlock();
        }
        return false;
    }


    private boolean isRequestEligibleForOnboarding(Elevator elevator, PassengerRequest request) {
        if(elevator.direction== Direction.UP) {
            if(request.source >= elevator.currentFloor && request.destination > elevator.currentFloor && request.source < request.destination) {
                return true;
            }
        }
        else if(elevator.direction== Direction.DOWN) {
            if(request.source<=elevator.currentFloor && request.destination < elevator.currentFloor && request.source > request.destination) {
                return true;
            }
        }
        return false;
    }


    @Override
    public void elevatorReadyForNewRequests(Elevator elevator) {
        int acceptanceCount = 0;
        for (PassengerRequest request : passengerRequests) {
            if(request.status== RequestStatus.WAITING && isRequestEligibleForOnboarding(elevator, request)) {
                if(assignRequest(request, elevator)) {
                    acceptanceCount++;
                }
            }
            if(acceptanceCount>=maxRequestAssignment) {
                break;
            }
        }
    }

    @Override
    public void elevatorPicksDirectionAndRequest(Elevator elevator) {
        for (PassengerRequest request : passengerRequests) {
            if(request.status == RequestStatus.WAITING) {
                if(assignRequest(request, elevator)) {
                    if(request.source == elevator.currentFloor) {
                        elevator.direction = request.destination > elevator.currentFloor ? Direction.UP : Direction.DOWN;
                    }
                    else{
                        elevator.direction = request.source > elevator.currentFloor ? Direction.UP : Direction.DOWN;
                    }
                    break;
                }
            }
        }
    }
}


public class ElevatorSystem {

    public static void main(String[] args) {

        ElevatorController controller = new ElevatorController(1);
        controller.floorRequest(new PassengerRequest(1, 1, 3, "smith", 1));
        controller.floorRequest(new PassengerRequest(2, 3, 8, "syed", 2));
        controller.floorRequest(new PassengerRequest(3, 4, 8, "customer", 3));
        controller.floorRequest(new PassengerRequest(4, 1, 12, "Ram", 4));
        controller.floorRequest(new PassengerRequest(5, 8, 5, "Karun", 5));
        controller.floorRequest(new PassengerRequest(6, 4, 0, "cameo", 6));

    }
}
