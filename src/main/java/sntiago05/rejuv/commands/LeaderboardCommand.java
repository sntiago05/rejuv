package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.LeaderboardEntry;
import sntiago05.rejuv.domain.LinkedMember;
import sntiago05.rejuv.domain.ServerLeaderboard;
import sntiago05.rejuv.services.LeaderboardService;
import sntiago05.rejuv.services.Messages;

@Component
public class LeaderboardCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardCommand.class);
    private static final String SUBCOMMAND_SERVER = "server";
    private static final int CHUNK_SIZE = 100;

    private final LeaderboardService leaderboardService;
    private final Messages messages;

    public LeaderboardCommand(LeaderboardService leaderboardService, Messages messages) {
        this.leaderboardService = leaderboardService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "leaderboard";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("leaderboard.description"))
                .setGuildOnly(true)
                .addSubcommands(new SubcommandData(SUBCOMMAND_SERVER, messages.get("leaderboard.server.description")));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply(messages.get("leaderboard.guild_only")).setEphemeral(true).queue();
            return;
        }

        if (!SUBCOMMAND_SERVER.equals(event.getSubcommandName())) {
            event.reply(messages.get("error.unknown_command")).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        long callerId = event.getUser().getIdLong();
        String guildName = guild.getName();

        List<LinkedMember> candidates;
        try {
            candidates = leaderboardService.findCandidates();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(event.getHook(), messages, log, "Leaderboard", callerId, "guild=" + guildName, ex);
            return;
        }

        if (candidates.isEmpty()) {
            event.getHook().editOriginal(messages.get("leaderboard.empty")).queue();
            return;
        }

        List<Long> userIds = candidates.stream()
                .map(LinkedMember::discordUserId)
                .distinct()
                .toList();

        Map<Long, Long> accountIdByUserId = candidates.stream()
                .collect(Collectors.toMap(LinkedMember::discordUserId, LinkedMember::accountId, (first, second) -> first));

        List<List<Long>> chunks = partition(userIds, CHUNK_SIZE);
        Map<Long, String> namesByUserId = new LinkedHashMap<>();
        retrieveChunks(guild, chunks.iterator(), namesByUserId, event.getHook(), callerId, guildName, accountIdByUserId);
    }

    private void retrieveChunks(Guild guild, Iterator<List<Long>> chunkIterator, Map<Long, String> namesByUserId,
                                InteractionHook hook, long callerId, String guildName, Map<Long, Long> accountIdByUserId) {
        if (!chunkIterator.hasNext()) {
            if (namesByUserId.isEmpty()) {
                hook.editOriginal(messages.get("leaderboard.empty")).queue();
                return;
            }
            respond(hook, callerId, guildName, namesByUserId, accountIdByUserId);
            return;
        }

        List<Long> chunk = chunkIterator.next();
        guild.retrieveMembersByIds(chunk)
                .onSuccess(members -> {
                    for (Member member : members) {
                        namesByUserId.put(member.getIdLong(), member.getEffectiveName());
                    }
                    retrieveChunks(guild, chunkIterator, namesByUserId, hook, callerId, guildName, accountIdByUserId);
                })
                .onError(failure -> {
                    RuntimeException ex = failure instanceof RuntimeException rex ? rex : new RuntimeException(failure);
                    CommandSupport.handleUnexpectedError(hook, messages, log, "Leaderboard", callerId, "guild=" + guildName, ex);
                });
    }

    void respond(InteractionHook hook, long callerId, String guildName, Map<Long, String> namesByUserId, Map<Long, Long> accountIdByUserId) {
        try {
            List<LinkedMember> membersInServer = new ArrayList<>();
            for (Map.Entry<Long, String> entry : namesByUserId.entrySet()) {
                Long accountId = accountIdByUserId.get(entry.getKey());
                if (accountId != null) {
                    membersInServer.add(new LinkedMember(entry.getKey(), accountId));
                }
            }

            ServerLeaderboard leaderboard = leaderboardService.buildLeaderboard(membersInServer);
            if (leaderboard.entries().isEmpty()) {
                hook.editOriginal(messages.get("leaderboard.empty")).queue();
                return;
            }

            MessageEmbed embed = buildEmbed(guildName, leaderboard, namesByUserId);
            hook.editOriginalEmbeds(embed).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Leaderboard", callerId, "guild=" + guildName, ex);
        }
    }

    private MessageEmbed buildEmbed(String guildName, ServerLeaderboard leaderboard, Map<Long, String> namesByUserId) {
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < leaderboard.entries().size(); i++) {
            LeaderboardEntry entry = leaderboard.entries().get(i);
            String rawName = namesByUserId.get(entry.discordUserId());
            String memberName = MarkdownSanitizer.escape(rawName != null ? rawName : String.valueOf(entry.discordUserId()));
            String rankText;
            if (entry.rankName() != null) {
                if (entry.subrank() > 0) {
                    rankText = messages.get("leaderboard.rank_value", entry.rankName(), String.valueOf(entry.subrank()));
                } else {
                    rankText = entry.rankName();
                }
            } else {
                rankText = messages.get("leaderboard.rank_unknown_tier", String.valueOf(entry.tier()));
            }
            String line = messages.get("leaderboard.entry", String.valueOf(i + 1), memberName, rankText);
            if (i > 0) {
                description.append("\n");
            }
            description.append(line);
        }

        return new EmbedBuilder()
                .setTitle(messages.get("leaderboard.title", guildName))
                .setDescription(description.toString())
                .setFooter(messages.get("leaderboard.footer",
                        String.valueOf(leaderboard.rankedCount()),
                        String.valueOf(leaderboard.unrankedCount())))
                .build();
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
