package sir.barchable.clash.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sir.barchable.clash.ClashServices;
import sir.barchable.clash.Main;
import sir.barchable.clash.ResourceException;
import sir.barchable.clash.model.SessionState;
import sir.barchable.clash.model.json.Village;
import sir.barchable.clash.model.json.Village.Building;
import sir.barchable.clash.protocol.*;

import java.io.File;
import java.io.IOException;
import java.io.EOFException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static sir.barchable.clash.model.ObjectType.OID_RADIX;
import static sir.barchable.clash.protocol.Pdu.Type.*;

/**
 * Clash server.
 *
 * @author Sir Barchable
 *         Date: 01/05/15
 */
public class ServerSession {
    private static final Logger log = LoggerFactory.getLogger(ServerSession.class);
    private AtomicBoolean running = new AtomicBoolean(true);
    private Connection clientConnection;
    private MessageFactory messageFactory;

    /**
     * Name of the loadout to use when attacking, or null to use the one from the home data.
     */
    private String loadout;

    /**
     * Attack the war layout?
     */
    private boolean war = true;

    private SessionState sessionState = new SessionState();

    /**
     * File system access.
     */
    private VillageManager villageManager;

    /**
     * Loadout management
     */
    private LoadoutManager loadoutManager;

    private boolean dirty;

    private ServerSession(ClashServices services, Connection clientConnection, Main.ServerCommand command) throws IOException {
        this.messageFactory = services.getMessageFactory();
        this.clientConnection = clientConnection;
        this.loadoutManager = new LoadoutManager(services.getLogic(), new File(services.getWorkingDir(), "loadouts"));
        this.villageManager = new VillageManager(messageFactory, loadoutManager, command.getHomeFile(), new File(services.getWorkingDir(), "villages"));

        String loadout = command.getLoadout();
        if (command.getLoadout() != null) {
            if (!loadoutManager.contains(loadout)) {
                log.warn("Loadout {} not found", loadout);
            } else {
                this.loadout = loadout;
            }
        }

        this.war = command.getWar();
    }

    public SessionState getSessionState() {
        return sessionState;
    }

    /**
     * Thread local session.
     */
    private static final InheritableThreadLocal<ServerSession> localSession = new InheritableThreadLocal<>();

    /**
     * Serve a clash session. This will block until processing completes, or until the calling thread is interrupted.
     * <p>
     * Normal completion is usually the result of an EOF on the input stream.
     */
    public static ServerSession newSession(ClashServices services, Connection clientConnection, Main.ServerCommand command) throws IOException {
        ServerSession session = new ServerSession(services, clientConnection, command);
        localSession.set(session);
        try {

            //
            // We run the uninterruptable IO in a separate thread to maintain an interruptable controlling thread from
            // which can stop processing by closing the input stream.
            //

            Thread t = new Thread(session::run, clientConnection.getName() + " server");
            t.start();
            t.join();

        } catch (InterruptedException e) {
            session.shutdown();
        } finally {
            localSession.set(null);
        }
        return session;
    }

    private void save() {
        villageManager.save();
        dirty = false;
    }

    /**
     * Process the key exchange then loop to process PDUs from the client.
     */
    private void run() {
        try {

            log.debug("Start Sending Packets");
            processRequests(clientConnection);

        } catch (PduException e) {
            log.error("Key exchange did not complete: " + e, e);
        }
    }

    private void processRequests(Connection connection) {

        int crashCount = 0;
        try {
            while (running.get()) {

                //
                // Read a request PDU
                //
                PduInputStream inStream = connection.getIn();
                if( inStream==null ) {
                    log.info("Ending. Likely App Crash. {} done", connection.getName());
                }

                try{
                    Pdu pdu = inStream.read();


                    log.debug("Incoming Pdu id:{} type:{}", pdu.getId(), pdu.getType());

                    Message request;
                    try {
                        request = messageFactory.fromPdu(pdu);
                    } catch (RuntimeException e) {
                        // Probably no type definition for the PDU
                        log.debug("Can't respond to {}: {}", pdu.getType(), e.getMessage());
                        e.printStackTrace(System.out);
                        continue;
                    }

                    //
                    // Create a response
                    //

                    Message response = null;

                    switch (pdu.getType()) {
                        case EndClientTurn:
                            response = endTurn(request);
                            break;

                        case AttackResult:
                            response = loadHome();
                            break;

                        case KeepAlive:
                            response = messageFactory.newMessage(ServerKeepAlive);
                            break;

                        case SetDeviceToken:
                            response = messageFactory.newMessage(ServerKeepAlive);
                            break;

                        case Login:
                            response = login(request);
                            break;

                        default:
                            log.debug("Not handling {} from {}", pdu.getType(), connection.getName());
                    }


                    if (response != null) {
                        log.debug(" Responding to {}", pdu.getType());
                        connection.getOut().write(messageFactory.toPdu(response));
                    } else {
                        log.debug(" No Responce to {}", pdu.getType());
                    }
                    crashCount = 0;
                //
                // Return the response to the client
                //
                } catch(IOException e){
                    log.error("Error Count {}", crashCount);
                    crashCount++;
                    if( crashCount >100 ) {
                        log.info("Ending. Likely App Crash.", connection.getName());
                        break;
                    } else {
                        continue;
                    }
                }
            }

            log.info("{} done", connection.getName());
        } catch (RuntimeException e) {
            log.info(
                "{} terminating: {}",
                connection.getName(),
                e
            );
        }
    }

    private Message login(Message loginMessage) throws IOException  {

        // A login Request requires the following
        //  Encription, LoginOk, OwnHomeData, UnknownInfoResponse, AvatarStream




        Long userId = loginMessage.getLong("userId");
        if (userId == null) {
            throw new PduException("No user id in login");
        }
        sessionState.setUserId(userId);

        Object clientSeed = loginMessage.get("clientSeed");
        if (clientSeed == null || !(clientSeed instanceof Integer)) {
            throw new PduException("Expected client seed in login message");
        }
        Clash7Random prng = new Clash7Random((Integer) clientSeed);

        //
        //  Encription
        //      Generate a nonce and pass it back to the client
        //
        Message encryptionMessage = messageFactory.newMessage(Encryption);

        byte[] nonce = new byte[24];
        ThreadLocalRandom.current().nextBytes(nonce); // generate a new key
        encryptionMessage.set("serverRandom", nonce);
        encryptionMessage.set("version", 1);

        clientConnection.getOut().write(messageFactory.toPdu(encryptionMessage));
        log.info("Sent Encription");


        clientConnection.setKey(prng.scramble(nonce));

        //
        // LoginOk
        //      Tell the client that all is well
        //

        Message loginOkMessage = messageFactory.newMessage(LoginOk);

        loginOkMessage.set("userId", userId);
        loginOkMessage.set("homeId", userId);
        loginOkMessage.set("userToken", loginMessage.get("userToken"));
        loginOkMessage.set("majorVersion", loginMessage.get("majorVersion"));
        loginOkMessage.set("minorVersion", loginMessage.get("minorVersion"));
        loginOkMessage.set("revision", 3);
        loginOkMessage.set("environment", "prod");
        loginOkMessage.set("loginCount", 60);
        loginOkMessage.set("timeOnline", 6110);
        loginOkMessage.set("f12", 14);
        loginOkMessage.set("facebookAppId", "297484437009394");
        loginOkMessage.set("lastLoginDate", "" + System.currentTimeMillis() / 1000);
        loginOkMessage.set("joinDate", "1436580824000");
        loginOkMessage.set("country", "US");

        clientConnection.getOut().write(messageFactory.toPdu(loginOkMessage));
        log.info("Sent LoginOk");

        //
        // OwnHomeData
        //      Your base info
        //
        clientConnection.getOut().write(messageFactory.toPdu(loadHome()));
        log.info("Sent OwnHomeData");


        
        //
        // UnknownInfoResponse
        //      ???
        //
        Message response = messageFactory.newMessage(UnknownInfoResponse);
        response.set("f1", 4);
        response.set("f2", 16);
        response.set("f3", 1651423);
        response.set("f4", 40);
        response.set("f5", 1077978);
        response.set("f6", 12);
        clientConnection.getOut().write(messageFactory.toPdu(response));
        log.info("Sent UnknownInfoResponse");


        // //
        // // AvatarStream
        // //      ???
        // //




        return null;
    }

    private Message endTurn(Message message) throws IOException {
        Message response = null;
        Message[] commands = message.getArray("commands");
        if (commands != null) {
            commandLoop: for (Message command : commands) {
                Integer id = command.getInt("id");
                if (id != null) {
                    switch (id) {
                        case 700:
                            response = loadEnemy();
                            break commandLoop;

                        case 603:
                            response = loadHome();
                            break commandLoop;

                        case 501:   // Move building
                            moveBuilding(command.getInt("x"), command.getInt("y"), command.getInt("buildingId"));
                            break;

                        case 512:   // Buy decoration
                            newBuilding(command.getInt("x"), command.getInt("y"), command.getInt("buildingId"));

                        default:
                            // We're lost; give up
                            log.debug("Not processing command {} from client", id);
                            break commandLoop;
                    }
                }
            }
        }

        if (dirty) {
            save();
        }

        return response;
    }

    /**
     * Add a new building, trap, or decoration.
     *
     * @param x x location
     * @param y y location
     * @param typeId building type id
     */
    private void newBuilding(int x, int y, int typeId) throws IOException {
        log.debug("Adding {} at {}, {}", typeId, x, y);
        int type = typeId / OID_RADIX;

        Village village = villageManager.getHomeVillage();
        Building building = new Building();
        building.x = x;
        building.y = y;
        building.data = typeId;

        switch (type) {
            case 1:     // Buildings
                village.buildings = appendBuilding(village.buildings, building);
                break;

            case 12:    // Traps
                village.traps = appendBuilding(village.traps, building);
                break;

            case 18:    // Decorations
                village.decos = appendBuilding(village.decos, building);
                break;
        }

        dirty = true;
    }

    private static Building[] appendBuilding(Building[] buildings, Building o) {
        int len = buildings.length;
        buildings = Arrays.copyOf(buildings, len + 1);
        buildings[len] = o;
        return buildings;
    }

    private void moveBuilding(int x, int y, int buildingId) {
        log.debug("Moving {} to {}, {}", buildingId, x, y);
        int offset = buildingId % OID_RADIX;
        int type = buildingId / OID_RADIX;

        Village village = villageManager.getHomeVillage();
        Building building = null;

        try {
            switch (type) {
                case 500:
                    building = village.buildings[offset];
                    break;

                case 504:
                    building = village.traps[offset];
                    break;

                case 506:
                    building = village.decos[offset];
                    break;

                default:
                    log.debug("You moved what?");
                    break;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            log.error("Couldn't find building {}", buildingId);
        }

        if (building != null) {
            building.x = x;
            building.y = y;
            dirty = true;
        }
    }

    private Message loadHome() throws IOException {
        Message village = villageManager.getOwnHomeData();
        // Set remaining shield to 0 to avoid annoying attack confirmation dialog
        village.set("remainingShield", 0);

        int secondsTimeStamp = (int) (System.currentTimeMillis() / 1000);

        // Give the base some age
        village.set("age", 4 );
        village.set("timeStamp", secondsTimeStamp - 4 );

        return village;
    }

    private void applyLoadout(Message village) throws IOException {
        if (loadout != null) {
            loadoutManager.applyLoadOut(village, loadout);
        }
    }

    private int nextVillage;

    private Message loadEnemy() throws IOException {
        Message village = villageManager.loadEnemyVillage(nextVillage++, war);
        if (village == null) {
            throw new ResourceException("No enemy villages. Have you captured some data with the proxy?");
        }
        village.set("timeStamp", (int) (System.currentTimeMillis() / 1000));
        applyLoadout(village);

        return village;
    }

    /**
     * A hint that processing should stop. Just sets a flag and waits for the processing threads to notice. If you
     * really want processing to stop in a hurry close the input streams.
     */
    public void shutdown() {
        running.set(false);
    }
}
