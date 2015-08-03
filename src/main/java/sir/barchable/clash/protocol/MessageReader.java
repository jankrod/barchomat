package sir.barchable.clash.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sir.barchable.clash.protocol.Protocol.StructDefinition;
import sir.barchable.clash.protocol.Protocol.StructDefinition.Extension;
import sir.barchable.clash.protocol.Protocol.StructDefinition.FieldDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.lang.StringBuilder;

/**
 * @author Sir Barchable
 *         Date: 6/04/15
 */
public class MessageReader {
    private static final Logger log = LoggerFactory.getLogger(MessageReader.class);

    private TypeFactory typeFactory;

    /**
     * Set this to store all fields after reading. Fields without names will be stored with the name "fieldN".
     */
    private boolean allFields = true;

    public MessageReader() {
        this(new TypeFactory());
    }

    public MessageReader(TypeFactory typeFactory) {
        this.typeFactory = typeFactory;
    }

    /**
     * Read a message.
     *
     * @param pdu the PDU containing the message
     * @return a map of field names -> field values, or null if the message ID isn't recognized
     */
    public Map<String, Object> readMessage(Pdu pdu) {
        Optional<String> messageName = typeFactory.getStructNameForId(pdu.getId());
        if (messageName.isPresent()) {
            try {
                MessageInputStream in = new MessageInputStream(new ByteArrayInputStream(pdu.getPayload()));
                return (Map<String, Object>) readValue(messageName.get(), in);
            } catch (IOException e) {
                throw new PduException(e);
            }
        } else {
            return null;
        }
    }

    /**
     * Read a value from the input stream. Delegates to {@link #readValue(TypeFactory.Type definition, MessageInputStream)}
     * after parsing the type with the configured {@link TypeFactory}.
     */
    public Object readValue(String typeName, MessageInputStream in) throws IOException {
        Objects.requireNonNull(typeName, "null definition");
        return readValue(typeFactory.resolveType(typeName), in);
    }

    /**
     * Read a value from the stream.
     *
     * @param definition the type of value to read
     * @param in where to read it from
     * @return the value, or null if the value was optional and not present in the stream
     */
    public Object readValue(TypeFactory.Type definition, MessageInputStream in) throws IOException {
        if (definition.isOptional() && !in.readBit()) {
            return null;
        }

        if (definition.isArray()) {
            return readArray(definition, in);
        } else if (definition.isPrimitive()) {
            return readPrimitive(definition, in);
        } else {
            return readStruct(definition, in);
        }
    }

    public int[] readEnd( MessageInputStream in) {
        ArrayList<Integer> extraBytes = new ArrayList<Integer>();
        try{
            while(true){
                Integer b = (Integer)in.readInt();
                if( b<0 ) {
                    break;
                }
                extraBytes.add( b ); 
            }
        } catch( Exception e ){}

        int[] array = new int[extraBytes.size()];
        for( int i=0; i<extraBytes.size(); i++) {
            array[i]= (int)extraBytes.get(i);
        }
        return array;

        //return (Byte[])extraBytes.toArray(new Byte[1] );
    }

    private Object readArray(TypeFactory.Type definition, MessageInputStream in) throws IOException {
        int length;
        if (definition.getLength() > 0) {
            // known length
            length = definition.getLength();
        } else {
            // length from stream
            length = in.readInt();
        }
        if (length < 0 || length > MessageInputStream.MAX_ARRAY_LENGTH) {
            throw new PduException("Array length out of bounds: " + length);
        }

        if (definition.isPrimitive()) {
            switch (definition.getPrimitiveType()) {
                case BYTE:
                    return in.readArray(new byte[length]);

                case INT:
                    return in.readArray(new int[length]);

                case LONG:
                    return in.readArray(new long[length]);

                case STRING:
                    return in.readArray(new String[length]);

                default:
                    throw new IllegalArgumentException("Don't know how to read arrays of type " + definition.getPrimitiveType());
            }
        } else {
            Object[] messages = new Object[length];
            for (int i = 0; i < length; i++) {
                try {
                    messages[i] = readValue(definition.getName(), in);
                } catch (PduException e) {
                    throw new PduException("Could not read element " + i + " of " + definition.getName() + "[]", e);
                }
            }
            return messages;
        }
    }

    private Object readPrimitive(TypeFactory.Type definition, MessageInputStream in) throws IOException {
        switch (definition.getPrimitiveType()) {
            case BOOLEAN:
                return in.readBit();

            case BYTE:
                return (byte) in.read();

            case INT:
                return in.readInt();

            case LONG:
                return in.readLong();

            case STRING:
                return in.readString();

            case ZIP_STRING:
                return in.readZipString();

            default:
                throw new IllegalArgumentException("Don't know how to read " + definition.getName());
        }
    }

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

    private Object readStruct(TypeFactory.Type definition, MessageInputStream in) {
        Map<String, Object> fields = new LinkedHashMap<>();
        int fieldIndex = 0;
        try {

            // Read fields
            StructDefinition struct = definition.getStructDefinition();
            for (FieldDefinition field : struct.getFields()) {
                fieldIndex++;
                Object value = readValue(field.getType(), in);
                if (field.getName() != null || allFields) {
                    fields.put(field.getName() != null ? field.getName() : "field" + fieldIndex, value);
                }
            }

            // Extra fields from extension?
            if (!struct.getExtensions().isEmpty()) {
                Integer id = (Integer) fields.get(TypeFactory.ID_FIELD);
                if (id == null) {
                    throw new PduException("id field missing from " + definition.getName());
                }
                Extension extension = struct.getExtension(id);
                if (extension != null) {
                    // Read extension to the struct
                    for (FieldDefinition field : extension.getFields()) {
                        fieldIndex++;
                        Object value = readValue(field.getType(), in);
                        if (field.getName() != null || allFields) {
                            fields.put(field.getName() != null ? field.getName() : "field" + fieldIndex, value);
                        }
                    }
                } else {
                    log.warn("No extension of {} with id {}", definition.getName(), id);
                }
            }


        } catch (RuntimeException | IOException e) {
            log.error("Pdu Error", e);
            throw new PduException("Could not read field " + fieldIndex + " of " + definition.getName(), e);
        }
        return fields;
    }


}
