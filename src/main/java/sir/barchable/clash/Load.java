package sir.barchable.clash;

import sir.barchable.clash.model.json.Village;
import sir.barchable.clash.model.json.WarVillage;
import sir.barchable.clash.protocol.Message;
import sir.barchable.clash.protocol.Pdu;
import sir.barchable.util.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Load messages from files.
 *
 * @author Sir Barchable
 *         Date: 18/05/15
 */
public class Load {
    private ClashServices services;
    private Main.LoadCommand command;

    public Load(ClashServices services, Main.LoadCommand command) {
        this.services = services;
        this.command = command;
    }

    public void run() throws IOException {
        File inFile = command.getInFile();

        String name = inFile.getName();

        if (name.endsWith(".pdu")) {
            try (FileInputStream in = new FileInputStream(inFile)) {
                Message message = services.getMessageFactory().fromStream(in);
                System.out.print( message.toString() );

                // Try again
                Pdu pdu2 = services.getMessageFactory().toPdu(message);
                Message m2 = services.getMessageFactory().fromPdu(pdu2);

                System.out.print( m2.toString() );

                // // And again
                // Pdu pdu3 = services.getMessageFactory().toPdu(m2);
                // Message m3 = services.getMessageFactory().fromPdu(pdu3);
                // Json.writePretty(getFields(m3), System.out);
            }
        } else {
            throw new IllegalArgumentException("Unknown file type (required .pdu)");
        }
    }

}
