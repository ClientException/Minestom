package net.minestom.server.command.builder;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import net.minestom.server.command.builder.parser.CommandParser;
import net.minestom.server.command.builder.parser.CommandSuggestionHolder;
import net.minestom.server.command.builder.parser.ValidSyntaxHolder;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Class responsible for parsing {@link Command}.
 */
public class CommandDispatcher {

    private final Map<String, Command> commandMap = new HashMap<>();
    private final Set<Command> commands = new HashSet<>();

    /**
     * Registers a command,
     * be aware that registering a command name or alias will override the previous entry.
     *
     * @param command the command to register
     */
    public void register(@NotNull Command command) {
        this.commandMap.put(command.getName().toLowerCase(), command);

        // Register aliases
        final String[] aliases = command.getAliases();
        if (aliases != null) {
            for (String alias : aliases) {
                this.commandMap.put(alias.toLowerCase(), command);
            }
        }

        this.commands.add(command);
    }

    public void unregister(@NotNull Command command) {
        this.commandMap.remove(command.getName().toLowerCase());

        final String[] aliases = command.getAliases();
        if (aliases != null) {
            for (String alias : aliases) {
                this.commandMap.remove(alias.toLowerCase());
            }
        }

        this.commands.remove(command);
    }

    @NotNull
    public Set<Command> getCommands() {
        return Collections.unmodifiableSet(commands);
    }

    /**
     * Gets the command class associated with the name.
     *
     * @param commandName the command name
     * @return the {@link Command} associated with the name, null if not any
     */
    @Nullable
    public Command findCommand(@NotNull String commandName) {
        commandName = commandName.toLowerCase();
        return commandMap.getOrDefault(commandName, null);
    }

    /**
     * Checks if the command exists, and execute it.
     *
     * @param source        the command source
     * @param commandString the command with the argument(s)
     * @return the command result
     */
    @NotNull
    public CommandResult execute(@NotNull CommandSender source, @NotNull String commandString) {
        CommandResult commandResult = parse(commandString);
        ParsedCommand parsedCommand = commandResult.parsedCommand;
        if (parsedCommand != null) {
            commandResult.commandData = parsedCommand.execute(source, commandString);
        }
        return commandResult;
    }

    /**
     * Parses the given command.
     *
     * @param commandString the command (containing the command name and the args if any)
     * @return the parsing result
     */
    @NotNull
    public CommandResult parse(@NotNull String commandString) {
        commandString = commandString.trim();

        // Split space
        final String[] parts = commandString.split(StringUtils.SPACE);
        final String commandName = parts[0];

        final Command command = findCommand(commandName);
        // Check if the command exists
        if (command == null) {
            return CommandResult.of(CommandResult.Type.UNKNOWN, commandName);
        }

        // Removes the command's name + the space after
        final String[] args = commandString.replaceFirst(Pattern.quote(commandName), "").trim().split(StringUtils.SPACE);

        // Find the used syntax
        ParsedCommand parsedCommand = findParsedCommand(command, args);

        CommandResult result = new CommandResult();
        result.input = commandString;
        result.parsedCommand = parsedCommand;
        if (parsedCommand != null && parsedCommand.executor != null) {
            // Syntax found
            result.type = CommandResult.Type.SUCCESS;
        } else {
            // Syntax not found, use the default executor (if any)
            result.type = CommandResult.Type.INVALID_SYNTAX;
            if (parsedCommand == null) { // Prevent overriding argument callback
                result.parsedCommand = ParsedCommand.withDefaultExecutor(command);
            }
        }
        return result;
    }

    @Nullable
    private ParsedCommand findParsedCommand(@NotNull Command command, @NotNull String[] args) {
        ParsedCommand parsedCommand = new ParsedCommand();
        parsedCommand.command = command;

        // The default executor should be used if no argument is provided
        {
            final CommandExecutor defaultExecutor = command.getDefaultExecutor();
            if (defaultExecutor != null && args[0].length() == 0) {
                parsedCommand.executor = defaultExecutor;
                parsedCommand.arguments = new Arguments();
                return parsedCommand;
            }
        }

        // SYNTAXES PARSING

        // All the registered syntaxes of the command
        final Collection<CommandSyntax> syntaxes = command.getSyntaxes();
        // Contains all the fully validated syntaxes (we later find the one with the most amount of arguments)
        List<ValidSyntaxHolder> validSyntaxes = new ArrayList<>(syntaxes.size());

        // Contains all the syntaxes that are not fully correct, used to later, retrieve the "most correct syntax"
        // Number of correct argument - The data about the failing argument
        Int2ObjectRBTreeMap<CommandSuggestionHolder> syntaxesSuggestions = new Int2ObjectRBTreeMap<>(Collections.reverseOrder());

        for (CommandSyntax syntax : syntaxes) {
            CommandParser.parse(syntax, syntax.getArguments(), args, validSyntaxes, syntaxesSuggestions);
        }

        // Check if there is at least one correct syntax
        if (!validSyntaxes.isEmpty()) {
            Arguments executorArgs = new Arguments();
            // Search the syntax with all perfect args
            final ValidSyntaxHolder finalValidSyntax = CommandParser.findMostCorrectSyntax(validSyntaxes, executorArgs);
            if (finalValidSyntax != null) {
                // A fully correct syntax has been found, use it
                final CommandSyntax syntax = finalValidSyntax.syntax;

                parsedCommand.syntax = syntax;
                parsedCommand.executor = syntax.getExecutor();
                parsedCommand.arguments = executorArgs;
                return parsedCommand;
            }

        }

        // No all-correct syntax, find the closest one to use the argument callback
        {
            // Get closest valid syntax
            if (!syntaxesSuggestions.isEmpty()) {
                final int max = syntaxesSuggestions.firstIntKey(); // number of correct arguments in the most correct syntax
                final CommandSuggestionHolder suggestionHolder = syntaxesSuggestions.get(max);
                final CommandSyntax syntax = suggestionHolder.syntax;
                final ArgumentSyntaxException argumentSyntaxException = suggestionHolder.argumentSyntaxException;
                final int argIndex = suggestionHolder.argIndex;

                // Found the closest syntax with at least 1 correct argument
                final Argument<?> argument = syntax.getArguments()[argIndex];
                if (argument.hasErrorCallback()) {
                    parsedCommand.callback = argument.getCallback();
                    parsedCommand.argumentSyntaxException = argumentSyntaxException;

                    return parsedCommand;
                }
            }
        }

        // No syntax found
        return null;
    }
}
