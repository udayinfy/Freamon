package de.blanksteg.freamon.hal;

import java.io.File;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.pircbotx.Channel;
import org.pircbotx.User;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;

import de.blanksteg.freamon.Configuration;
import de.blanksteg.freamon.MessageSanitizer;
import de.blanksteg.freamon.db.Database;
import de.blanksteg.freamon.irc.Network;
import de.blanksteg.freamon.nlp.PhraseAnalyzer;
import de.blanksteg.freamon.nlp.Word;
import de.blanksteg.freamon.nlp.WordType;

/**
 * The FreamonHal class can be regarded as the brain of the chatbot that is responsible for learning from messages,
 * tracking conversations and creating responses relevant for conversations. For this it maintains an instance of the
 * {@link JMegaHal} class that represents the bot's current understanding of the language spoken in the messages it is
 * presented. Since this model contains transient data like the names of people mentioned in phrases, an additional set
 * of known people is stored which is later used to replace any possibly invalid references to people with the ones
 * currently participating in a conversation. To actually remember what is happening in which conversation, FreamonHal
 * contains a mapping from conversation names to their last known state. To analyze incoming messages for their uttered
 * words, an instance of the {@see PhraseAnalyzer} is stored.
 *
 * Responses by FreamonHal are supposed to be somewhat relevant to what is actually being said in the application. To
 * achieve this, any incoming message in a conversation is split up into a sequence of {@link Word} instances by the
 * {@link PhraseAnalyzer} object used in this class. This list of words is then put into the appropriate
 * {@link ConversationState} which sorts them according to their priorities defined in {@link WordType}. When queried to
 * generate a response relevant to a specific conversation, the sorted set of recent words is retrieved from its last
 * known state and one of the most important words selected as the "focus" of the response. Said focus is then passed to
 * {@link JMegaHal#getSentence(String)} as the basis for a generated reply. Because JMegalHal inserts nicknames of
 * people not even active in the conversation, any mention of a known IRC user is replaced with some other user from
 * {@link ConversationState#getTalkers()}.
 *
 * FreamonHal makes this functionality publicly available via the
 * {@link FreamonHal#generateRelevantPrivateMessage(PrivateMessageEvent) and
 *
 * @link FreamonHal#generateRelevantPublicMessage(MessageEvent) methods which react to the two types of conversation
 *       indicated by their name.
 *
 *       This class is meant to be serialized to persist the brainstate across executions. However, because the tracking
 *       of conversations is stored in {@link WeakReference}s and the PhraseAnalyzer requires special deserialization,
 *       it contains transient fields that need to be manually reinitialized with the {@link FreamonHal#reinit(File)}
 *       method after the object is read.
 *
 * @author Marc Müller
 */
public class FreamonHal extends ListenerAdapter<Network> implements Serializable {
    /** The log4j logger to output logging info to. */
    private static final Logger l = Logger.getLogger("de.blanksteg.freamon.hal");
    private static final long serialVersionUID = 4526485508603032520L;
    private static final String NICKNAME = "[a-zA-Z-\\_]+";

    /** The MegaHAL analyzing the received messages. */
    private final Hal hal;
    /** Set of known people actually chatting. */
    private final Set<String> peopleNames = new HashSet<String>();

    /** A mapping of each conversation name to its last known state. */
    private transient Map<String, ConversationState> conversationMemory = new WeakHashMap<String, ConversationState>();
    /** PhraseAnalyzer instance used to retrieve word lists. */
    private transient PhraseAnalyzer analyzer = new PhraseAnalyzer(Configuration.SNLP_MODEL);
    /** The file this brain was loaded from. Basically irrelevant to this class but used to store brains. */
    private transient File baseFile;
    /** Simple boolean flag to avoid double reinitialization. */
    private transient boolean initialized = false;

    /** The characters to replace in nicknames that would accidentally highlight people */
    private static final HashMap<Character, Character[]> charReplacements = new HashMap<Character, Character[]>();
    /** The maximum amount of tries per nickname to replace it, prevents infinite loops */
    private static final int MAX_REPLACEMENT_TRIES = 10;

    static {
        // Fill the character replacements for accidental highlight prevention
        charReplacements.put('a', new Character[] { 'e' });
        charReplacements.put('b', new Character[] { 'd', 'p' });
        charReplacements.put('c', new Character[] { 'q', 'k' });
        charReplacements.put('d', new Character[] { 'b', 'p' });
        charReplacements.put('e', new Character[] { 'a' });
        charReplacements.put('f', new Character[] { 'b' });
        charReplacements.put('g', new Character[] { 'q' });
        charReplacements.put('h', new Character[] { 'k' });
        charReplacements.put('i', new Character[] { 'u', 'y' });
        charReplacements.put('j', new Character[] { 'y' });
        charReplacements.put('k', new Character[] { 'c', 'q' });
        charReplacements.put('l', new Character[] { 'w', 'r' });
        charReplacements.put('m', new Character[] { 'n' });
        charReplacements.put('n', new Character[] { 'm' });
        charReplacements.put('o', new Character[] { 'u' });
        charReplacements.put('p', new Character[] { 'b', 'd' });
        charReplacements.put('q', new Character[] { 'c', 'k' });
        charReplacements.put('r', new Character[] { 'l' });
        charReplacements.put('s', new Character[] { 'c', 'z' });
        charReplacements.put('t', new Character[] { 'p', 'd' });
        charReplacements.put('u', new Character[] { 'o' });
        charReplacements.put('v', new Character[] { 'f', 'w' });
        charReplacements.put('w', new Character[] { 'v' });
        charReplacements.put('x', new Character[] { 'z' });
        charReplacements.put('y', new Character[] { 'i' });
        charReplacements.put('z', new Character[] { 'c', 'x' });
    }

    /**
     * Create a new instance that was loaded from the given file.
     *
     * @param newBaseFile
     *            The base file this instance was from.
     */
    public FreamonHal(final Hal hal) {
        this.hal = hal;
        initialized = true;
    }

    /**
     * Create a new instance that was loaded from the given file.
     *
     * @param newBaseFile
     *            The base file this instance was from.
     */
    public FreamonHal(final File newBaseFile) {
        hal = new JMegaHal();
        baseFile = newBaseFile;
        initialized = true;
    }

    /**
     * Create a new instance that was loaded from the given database.
     *
     * @param database
     *            The database used to store the knowledge in.
     */
    public FreamonHal(final Database database) {
        hal = new H2MegaHal(database);
        baseFile = null;
        initialized = true;

        try {
            final ResultSet result = database.query("SELECT NAME FROM PEOPLENAMES");
            while (result.next()) {
                peopleNames.add(result.getString("NAME"));
            }
        } catch (final SQLException ex) {
            l.error("Couldn't load names", ex);
        }
    }

    /**
     * Reinitialization method for transient fields. This is supposed to be called after loading the object via an
     * {@link ObjectInputStream}.
     *
     * If the instance has already been initialized an {@link IllegalStateException} is thrown.
     *
     * @param newBaseFile
     *            The file this was loaded from.
     * @throws IllegalStateException
     *             when initialization was already done.
     */
    public synchronized void reinit(final File newBaseFile) {
        if (initialized) {
            throw new IllegalStateException("Can't reinitialize an instance of this class twice.");
        }

        conversationMemory = new WeakHashMap<String, ConversationState>();
        baseFile = newBaseFile;

        if (analyzer == null) {
            analyzer = new PhraseAnalyzer(Configuration.SNLP_MODEL);
        }

        initialized = true;
    }

    /**
     * Saves this instance to either a brain file or database.
     */
    public void save() {
        if (hasBrain()) {
            // When we have a base file, save HAL by writing this object to it
            l.debug("Writing the brain to the disk at " + baseFile + ".");
            SerializedFreamonHalTools.writeThreaded(baseFile, this);
            l.debug("Done writing the brain to " + baseFile + ".");
        } else {
            final Database db = hal.getDatabase();
            for (final String name : peopleNames) {
                final String nameEsc = StringEscapeUtils.escapeSql(name);
                try {
                    if (!db.query("SELECT * FROM PEOPLENAMES WHERE NAME='" + nameEsc + "'").first()) {
                        db.insert("INSERT INTO PEOPLENAMES (NAME) VALUES ('" + nameEsc + "')");
                    }
                } catch (final SQLException ex) {
                    l.error("Couldn't insert name", ex);
                }
            }

            hal.save();
        }
    }

    /**
     * Checks whether or not this instance is using a brain file or a database.
     *
     * @return True iff using a brain file
     */
    public boolean hasBrain() {
        return baseFile != null;
    }

    /**
     * Let the underlying MegaHAL instance learn from the given sentence. Before training, the sentence is filtered via
     * {@link MessageSanitizer#filterMessage(String)}.
     *
     * @param sentence
     *            The sentence to train.
     */
    public synchronized void addSentence(final String sentence) {
        final String filtered = MessageSanitizer.filterMessage(sentence);

        if (filtered != null && !MessageSanitizer.emptyString(filtered)) {
            final long start = System.currentTimeMillis();
            hal.addSentence(filtered);
            final long time = System.currentTimeMillis() - start;
            l.trace("[" + time + "]: Learning a new sentence: " + filtered);
        } else {
            l.trace("The following message was null or empty after filtering: " + sentence);
        }
    }

    /**
     * Remember the given name as someone to be replaced with currently talking people.
     *
     * @param name
     *            The person's name.
     */
    public synchronized void addPeopleName(final String name) {
        if (!Configuration.getLearnNames()) {
            return;
        }

        if (name.trim().length() < 3 || peopleNames.contains(name) || !name.matches(NICKNAME)) {
            return;
        }

        l.trace("Remembering people name: " + name);
        peopleNames.add(name);
    }

    /**
     * Traverses the given collection of trainers and performs their training with this instance.
     *
     * @param trainers
     *            The trainers to iterate over.
     */
    public synchronized void trainAll(final Collection<Trainer> trainers) {
        l.debug("Starting training with " + trainers.size() + " trainers.");
        for (final Trainer trainer : trainers) {
            trainer.trainAll(this);
        }
    }

    /**
     * This method handles the common actions to be performed upon receiving a message:
     * <ul>
     * <li>Update the MegaHAL.</li>
     * <li>Remember the name of a person that is chatting.</li>
     * <li>Remember active participants for the conversation in its respective state.</li>
     * <li>Remember uttered words for the conversation in its respective state.</li>
     * </ul>
     *
     * A somewhat peculiar aspect is the "us" parameter, which should be the name of the bot that received the message.
     * It is needed to avoid remembering the bot itself as a talker and possibly responding to itself.
     *
     * @param us
     *            The nickname of the bot receiving the message.
     * @param conversation
     *            The name of the conversation the message was received in.
     * @param sender
     *            The person who sent the message.
     * @param message
     *            The message itself.
     */
    private void handleMessage(final String us, final String conversation, final String sender, String message) {
        l.trace("Handling an incoming message in the conversation " + conversation + " from " + sender + ".");
        l.trace("The message is: " + message);
        addPeopleName(sender);
        addSentence(message);

        final ConversationState state = ensureConversationState(conversation);
        message = MessageSanitizer.filterMessage(message);
        final List<Word> words = analyzer.analyzePhrase(message);

        // Remove our name as a relevant word
        for (int i = 0; i < words.size(); ++i) {
            if (StringUtils.equalsIgnoreCase(words.get(i).getWord(), us)) {
                words.remove(i);
                --i;
            }
        }

        retainWords(state, words);

        if (!sender.equals(us)) {
            retainTalker(state, sender);
        }
    }

    /**
     * Reacting to a {@link PrivateMessageEvent} only requires us to handle the message for the person who sent it.
     */
    @Override
    public synchronized void onPrivateMessage(final PrivateMessageEvent<Network> event) {
        if (event == null) {
            return;
        }

        if (event.getBot().isIgnored(event.getUser().getNick())) {
            return;
        }

        final String message = event.getMessage();
        if (message.startsWith("!")) {
            return;
        }

        final String sender = event.getUser().getNick();
        l.trace("Got a private message from " + sender + ": " + message);
        handleMessage(event.getBot().getNick(), sender, sender, message);
    }

    /**
     * Reacting to a {@link MessageEvent} only requires us to handle the message for the channel the message was sent
     * in.
     */
    @Override
    public synchronized void onMessage(final MessageEvent<Network> event) {
        if (event == null) {
            return;
        }

        if (event.getBot().isIgnored(event.getUser().getNick())) {
            return;
        }

        final String message = event.getMessage();
        final String sender = event.getUser().getNick();
        final String channel = event.getChannel().getName();
        l.trace("Got a message in channel " + channel + " from " + sender + ".");
        handleMessage(event.getBot().getNick(), channel, sender, message);
    }

    /**
     * Generates an original message via {@link JMegaHal#getSentence()} and returns it.
     *
     * @return The generated sentence.
     */
    private String generateOriginalMessage() {
        final String message = MessageSanitizer.beautifyMessage(hal.getSentence());
        l.debug("Generated original message: " + message);
        return message;
    }

    /**
     * Generates a message relevant to the given state of a conversation. Relevant messages are generated based on one
     * of the most important words recently uttered in that conversation. The response is then modified to refer to
     * people active in the conversation.
     *
     * @param state
     *            The state to respond to.
     * @return The generated message.
     */
    private String generateRelevantMessage(final ConversationState state) {
        l.trace("Generating a relevant message for the conversation " + state.getName() + ".");
        final TreeSet<Word> words = state.getWords();
        l.trace(words);

        if (words != null && words.size() > 0) {
            int bound = getRandomSelectionBound(words.size()) - 1;
            l.trace("Selecting the " + bound + "th relevant word as the focus.");
            final Iterator<Word> iter = words.descendingIterator();
            for (; bound > 0; bound--) {
                iter.next();
            }

            while (iter.hasNext()) {
                final Word focus = iter.next();
                l.debug("Focus word is: " + focus + ".");
                String message = attemptMessageGeneration(focus.getWord());
                message = appropriateNicknames(message, state);
                message = MessageSanitizer.beautifyMessage(message);
                if (message != null) {
                    l.debug("Generated relevant message: " + message);
                    return message;
                } else {
                    l.debug("Message was null.");
                }
            }
        }

        l.trace("Could not find a way to make the message relevant. Falling back on doing an original message.");
        return generateOriginalMessage();
    }

    /**
     * This is a small helper method that just bruteforces message generation with MegaHAL since it sometimes returns
     * null for no apparent reason.
     *
     * @param focus
     *            The word to give to {@link JMegaHal#getSentence(String)}.
     * @return The generated message or "I have no idea." if MegaHAL never returned anything.
     */
    private String attemptMessageGeneration(final String focus) {
        String response = null;
        int attempts = Configuration.JMEGAHAL_ATTEMPTS;
        while (attempts > 0 && response == null) {
            response = hal.getSentence(focus);
            attempts--;
        }

        if (response == null) {
            response = "I have no idea.";
        }

        return response;
    }

    /**
     * Generates a response relevant to the channel the given {@link MessageEvent} was received in. The triggers for
     * receiving a message are called to ensure an up-to-date conversation state.
     *
     * @param event
     *            The event caused by the message.
     * @return The generated reply.
     */
    public synchronized String generateRelevantPublicMessage(final MessageEvent<Network> event) {
        onMessage(event);

        final Channel channel = event.getChannel();
        final String name = channel.getName();
        final ConversationState state = ensureConversationState(name);
        return preventHighlighting(generateRelevantMessage(state), event);
    }

    /**
     * Generates a response relevant to the private conversation with the person that caused the
     * {@link PrivateMessageEvent}. The triggers for receiving a message are called to ensure an up-to-date conversation
     * state.
     *
     * @param event
     *            The event caused by the message.
     * @return The generated reply.
     */
    public synchronized String generateRelevantPrivateMessage(final PrivateMessageEvent<Network> event) {
        onPrivateMessage(event);

        final User user = event.getUser();
        final String name = user.getNick();
        final ConversationState state = ensureConversationState(name);
        return generateRelevantMessage(state);
    }

    /**
     * Remember the recently active participant for the given conversation.
     *
     * @param conversation
     *            The conversation to update.
     * @param talker
     *            The person talking.
     */
    private void retainTalker(final ConversationState conversation, final String talker) {
        l.trace("Remembering talker " + talker + " for the conversation " + conversation.getName() + ".");
        conversation.addTalker(talker);
    }

    /**
     * Remember the list of words as recently uttered for the given conversation.
     *
     * @param conversation
     *            The conversation to update.
     * @param words
     *            The words to remember.
     */
    private void retainWords(final ConversationState conversation, final List<Word> words) {
        if (words.size() == 0) {
            return;
        }

        conversation.getWords().clear();
        for (final Word word : words) {
            conversation.addWord(word);
        }
    }

    /**
     * Returns a random value that limits the selection among the given amount of items.
     *
     * @param size
     *            The amount of items to choose from.
     * @return The random choice.
     */
    private int getRandomSelectionBound(final int size) {
        if (size > 1) {
            return Configuration.RNG.nextInt((int) (size * 0.7));
        } else {
            return size;
        }
    }

    /**
     * Either creates or retrieves the known state for the given conversation name and returns it.
     *
     * @param conversation
     *            The conversation name to get the state of.
     * @return Either the created or retrieved state.
     */
    private ConversationState ensureConversationState(final String conversation) {
        ConversationState state = null;
        if (conversationMemory.containsKey(conversation)) {
            state = conversationMemory.get(conversation);
        } else {
            l.trace("No record found for conversation " + conversation + ". Creating one now.");
            state = new ConversationState(conversation);
            conversationMemory.put(conversation, state);
        }

        return state;
    }

    /**
     * Replace any mention of a person in {@link FreamonHal} with recent talkers from
     * {@link ConversationState#getTalkers()} to make personal references relevant. The modified message is then
     * returned.
     *
     * @param message
     *            The message to alter.
     * @param state
     *            The state to get talkers from.
     * @return The altered message.
     */
    private String appropriateNicknames(String message, final ConversationState state) {
        if (!Configuration.getLearnNames()) {
            return message;
        }

        l.trace("Making the nicknames in the following sentence appropriate: ");
        l.trace(message);

        final Set<String> orderedTalkers = state.getTalkers();
        final List<String> talkers = new ArrayList<String>(orderedTalkers.size());
        talkers.addAll(orderedTalkers);
        Collections.shuffle(talkers);
        final String lastTalker = state.getLastTalker();

        // Find the nicknames
        final Set<String> contained = new HashSet<String>(talkers.size());
        final Iterator<String> people = peopleNames.iterator();
        boolean lastTalkerFound = false;

        while (people.hasNext() && talkers.size() != contained.size()) {
            final String currentPerson = people.next();
            if (message.matches("(?i)(^|.*\\W)" + currentPerson + "(\\W.*|$)")) {
                if (currentPerson.equals(lastTalker)) {
                    lastTalkerFound = true;
                } else {
                    l.trace("Found person to replace: " + currentPerson);
                    contained.add(currentPerson);
                }
            }
        }

        if (!contained.isEmpty()) {
            // Make sure the one speaking to Freamon gets mentioned first to avoid unneeded "name: " prefixes
            talkers.remove(lastTalker);
            if (!lastTalkerFound) {
                talkers.add(0, lastTalker);
            }

            // Replace the nicknames
            final Iterator<String> match = contained.iterator();
            final Iterator<String> repl = talkers.iterator();

            while (match.hasNext() && repl.hasNext()) {
                final String oldName = match.next();
                final String newName = repl.next();
                l.trace("Replacing " + oldName + " with " + newName);
                message = message.replaceAll("(?i)" + oldName, newName);
            }
        }

        return message;
    }

    /**
     * Prevent accidental highlighting of users in the channel. Highlighting the sender is still allowed.
     *
     * @param message
     *            The message to filter
     * @param event
     *            The event that the message was sent with
     *
     * @return The message with accidental highlights replace
     */
    private String preventHighlighting(String message, final MessageEvent<Network> event) {
        final String botNickLower = event.getBot().getNick().toLowerCase();
        final String senderNickLower = event.getUser().getNick().toLowerCase();
        String messageLower = message.toLowerCase();

        // Check which users are in the channel and filter their names
        final Set<User> userSet = event.getChannel().getUsers();
        for (final User user : userSet) {
            final String nick = user.getNick();
            final String nickLower = nick.toLowerCase();

            // We're allowed to highlight the sender and ourselves, or any one-letter name
            if (senderNickLower.contains(nickLower) || botNickLower.contains(nickLower) || nick.length() == 1) {
                continue;
            }
            int tries = 0;

            // Replace each occurence separately
            while (messageLower.contains(nickLower) && tries <= MAX_REPLACEMENT_TRIES) {
                // Get the nick with correct capitalisation
                final int nickIndex = messageLower.indexOf(nickLower);
                String newNick = message.substring(nickIndex, nickIndex + nick.length());

                // Decide which char to replace
                final int pos = Configuration.RNG.nextInt(newNick.length());
                final Character selectedChar = newNick.charAt(pos);
                Character replacementChar = null;

                if (Character.isDigit(selectedChar)) {
                    // Digits can just receive another number
                    replacementChar = Integer.toString(Configuration.RNG.nextInt(10)).charAt(0);
                } else {
                    // Determine the replacement char based on its mapping
                    final Character[] replacements = charReplacements.get(Character.toLowerCase(selectedChar));
                    if (replacements != null) {
                        // Randomly pick a possible replacement and make it match the case
                        replacementChar = replacements[Configuration.RNG.nextInt(replacements.length)];
                        if (Character.isUpperCase(selectedChar)) {
                            replacementChar = Character.toUpperCase(replacementChar);
                        }
                    }
                }

                // If we found a good replacement, switch the chars
                if (replacementChar != null) {
                    newNick = newNick.substring(0, pos) + replacementChar + newNick.substring(pos + 1);
                    message = message.replaceFirst("(?i)" + nick, newNick);
                    messageLower = message.toLowerCase();
                    tries = 0;
                } else {
                    ++tries;
                }
            }
        }

        return message;
    }
}
