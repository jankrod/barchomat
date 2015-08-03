package sir.barchable.clash.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sir.barchable.clash.model.json.Replay;
import sir.barchable.clash.model.json.WarVillage;
import sir.barchable.clash.protocol.*;
import sir.barchable.clash.protocol.Pdu.Type;
import sir.barchable.util.Json;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static sir.barchable.clash.protocol.Pdu.Type.*;
import static sir.barchable.util.NoopCipher.NOOP_CIPHER;

/**
 * Save PDU message payloads to a directory.
 *
 * @author Sir Barchable
 *         Date: 25/04/15
 */
public class MessageSaver implements PduFilter {
    private static final Logger log = LoggerFactory.getLogger(MessageSaver.class);

    private MessageFactory messageFactory;
    private File saveDir;
    private Set<Type> types;

    /**
     * Helper to make a saver that writes any vilage PDU
     *
     * @param saveDir where to save the messages
     */
    static public MessageSaver VillageSaver(MessageFactory messageFactory, File saveDir) throws FileNotFoundException {
        return new MessageSaver(
            messageFactory,
            saveDir,
            OwnHomeData, VisitedHomeData, EnemyHomeData, WarHomeData, HomeBattleReplayData
        );
    }


    static public MessageSaver SaveAll(MessageFactory messageFactory, File saveDir) throws FileNotFoundException {
        return new MessageSaver(
            messageFactory,
            saveDir
        );
    }

    /**
     * Construct a MessageSaver for specified message types.
     *
     * @param saveDir where to save the messages to
     * @param types the IDs of the PDUs to save
     */
    public MessageSaver(MessageFactory messageFactory, File saveDir, Type... types) throws FileNotFoundException {
        if (!saveDir.exists()) {
            throw new FileNotFoundException(saveDir.getName());
        }
        this.messageFactory = messageFactory;
        this.saveDir = saveDir;
        this.types = new HashSet<>(Arrays.asList(types));
    }

    @Override
    public Pdu filter(Pdu pdu) throws IOException {
        Type type = Type.valueOf(pdu.getId());
        try {

            if ( types.size()==0 ||  types.contains(type)) {

                String villageName = guessName(pdu);
                String name = String.format("%s[%3$s]%2$tF-%2$tH-%2$tM-%2$tS.pdu", type, new Date(), villageName);
                File file = new File(saveDir, name);
                try (PduOutputStream out = new PduOutputStream(new FileOutputStream(file), NOOP_CIPHER)) {
                    out.write(pdu);
                }


                try{
                    Message message = messageFactory.fromPdu(pdu);
                    name = String.format("%s[%3$s]%2$tF-%2$tH-%2$tM-%2$tS.json", type, new Date(), villageName);
                    PrintWriter writer = new PrintWriter(saveDir+"/"+name,"UTF-8");
                    writer.println(message.toString());
                    writer.close();
                } catch(Exception e){
                    log.warn("Can't write JSON for the pdu:{}",type);
                }
            }
        } catch (PduException | IOException e) {
            log.error("Couldn't save Pdu", e);
        }
        return pdu;
    }

    /**
     * Try to extract the village name from a PDU.
     *
     * @return the {@link #sanitize(String) sanitized} village name, or "anon" if it can't be determined
     */
    private String guessName(Pdu pdu) {
        String villageName = "";

        try {
            Message message = messageFactory.fromPdu(pdu);
            switch (message.getType()) {
                case OwnHomeData:
                case VisitedHomeData:
                case EnemyHomeData:
                    villageName = message.getLong("homeId").toString();
                    break;

                case WarHomeData:
                    WarVillage warVillage = Json.valueOf(message.getString("homeVillage"), WarVillage.class);
                    villageName = warVillage.name;
                    break;

                case HomeBattleReplayData:
                    Replay replay = Json.valueOf(message.getString("replay"), Replay.class);
                    villageName = replay.defender.name;
                    break;
            }
        } catch (Exception e) {
            log.warn("Couldn't extract name from pdu {}: {}", pdu.getId(), e.toString());
        }

        return sanitize(villageName);
    }

    /**
     * Make a string file system friendly.
     */
    private String sanitize(String s) {
        int len = s.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if ( ch < ' ' || ":\\/]".indexOf(ch) != -1) {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
