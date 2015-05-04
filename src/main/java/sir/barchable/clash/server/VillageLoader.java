package sir.barchable.clash.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sir.barchable.clash.protocol.Message;
import sir.barchable.clash.protocol.MessageFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import static sir.barchable.clash.protocol.Pdu.Type.EnemyHomeData;
import static sir.barchable.clash.protocol.Pdu.Type.OwnHomeData;

/**
 * Load saved villages from the specified directory. Villages are typically saved by a
 * {@link sir.barchable.clash.proxy.MessageSaver} hooked into the proxy when the {@code -s} save flag is passed on
 * startup.
 *
 * @author Sir Barchable
 */
public class VillageLoader {
    private static final Logger log = LoggerFactory.getLogger(VillageLoader.class);

    private static final Pattern HOME_PATTERN = Pattern.compile("OwnHomeData.*\\.pdu");
    private static final Pattern ENEMY_HOME_PATTERN = Pattern.compile("EnemyHomeData.*\\.pdu");

    private MessageFactory messageFactory;
    private File home;
    private File[] enemyHomes;

    public VillageLoader(MessageFactory messageFactory, File dir) throws IOException {
        this.messageFactory = messageFactory;

        Optional<File> homeFile = Files.walk(dir.toPath())
            .map(Path::toFile)
            .filter(file -> HOME_PATTERN.matcher(file.getName()).matches())
            .findFirst();

        if (homeFile.isPresent()) {
            home = homeFile.get();
        } else {
            throw new FileNotFoundException("No home village file found in " + dir);
        }

        enemyHomes = Files.walk(dir.toPath())
            .map(Path::toFile)
            .filter(file -> ENEMY_HOME_PATTERN.matcher(file.getName()).matches())
            .toArray(File[]::new);
    }

    public File getHome() {
        return home;
    }

    /**
     * Load the user's home.
     */
    public Message loadHomeVillage() throws IOException {
        try (FileInputStream in = new FileInputStream(home)) {
            return messageFactory.fromStream(OwnHomeData, in);
        }
    }

    /**
     * Load the nth saved enemy home. The index will be wrapped if it is longer than the array length.
     *
     * @param village the index of the home to load
     * @return the village, or null if there are no saved enemy villages
     */
    public Message loadEnemyVillage(int village) throws IOException {
        if (village < 0) {
            throw new IllegalArgumentException();
        }
        if (enemyHomes.length == 0) {
            return null;
        }
        File enemyHome = enemyHomes[village % enemyHomes.length];
        try (FileInputStream in = new FileInputStream(enemyHome)) {
            log.debug("loading enemy village {}", enemyHome);
            return messageFactory.fromStream(EnemyHomeData, in);
        }
    }
}