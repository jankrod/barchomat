package sir.barchable.clash.proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sir.barchable.clash.protocol.Pdu;
import sir.barchable.clash.protocol.PduInputStream;
import sir.barchable.clash.protocol.PduOutputStream;
import sir.barchable.clash.ClashServices;
import sir.barchable.clash.protocol.Message;
import sir.barchable.clash.protocol.MessageFactory;

import java.io.EOFException;
import java.io.IOException;

/**
 * A filtered pipe for clash streams.
 *
 * @author Sir Barchable
 *         Date: 15/04/15
 */
class Pipe {

    private static final Logger log = LoggerFactory.getLogger(Pipe.class);
    /**
     * Name for debugging
     */
    private final String name;

    /**
     * Close the sink on source EOF?
     */
    private boolean propagateEof = true;

    private PduInputStream source;
    private PduOutputStream sink;

    private ClashServices services = ClashServices.getInstance();
    private MessageFactory messageFactory = services.getMessageFactory();


    public Pipe(String name, PduInputStream source, PduOutputStream sink) {
        this.name = name;
        this.source = source;
        this.sink = sink;
    }

    /**
     * Pipe one PDU through the supplied filter.
     */
    public void filterThrough(PduFilter filter) throws IOException {
        // Read
        Pdu pdu;

        try {
            pdu = source.read();
        } catch (EOFException eof) {
            if (propagateEof) {
                try {
                    sink.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            throw eof;
        }
        
        // switch (pdu.getType()) {
        //     case Encryption:
        //     case LoginOk:
        //     case UnknownInfoResponse:
        //     case GlobalChatLine:
        //     case EndClientTurn:
                
        //         Message m = messageFactory.fromPdu(pdu);
        //         if( m!=null ) {
        //             log.debug("Transformed {} - {}", this.name, pdu.getType());
        //             pdu = messageFactory.toPdu(m);
        //         }

        //         break;
        // }

        // Transform
        Pdu filteredPdu = filter.filter(pdu);




        if (filteredPdu != null) {
            // Write
            sink.write(filteredPdu);
        };
    }

    public String getName() {
        return name;
    }

    public PduInputStream getSource() {
        return source;
    }

    public PduOutputStream getSink() {
        return sink;
    }

    public void setPropagateEof(boolean propagateEof) {
        this.propagateEof = propagateEof;
    }
}
