package sntiago05.rejuv.commands;

import java.util.List;
import java.util.OptionalLong;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.HeroUsage;
import sntiago05.rejuv.domain.ProfileSummary;
import sntiago05.rejuv.services.AssetImages;
import sntiago05.rejuv.services.IconCatalog;
import sntiago05.rejuv.services.Messages;
import sntiago05.rejuv.services.NoMatchHistoryException;
import sntiago05.rejuv.services.ProfileService;

@Component
public class ProfileCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(ProfileCommand.class);
    private static final String USER_OPTION = "user";

    private final ProfileService profileService;
    private final Messages messages;
    private final AssetImages assetImages;
    private final IconCatalog iconCatalog;

    public ProfileCommand(ProfileService profileService, Messages messages,
                          AssetImages assetImages, IconCatalog iconCatalog) {
        this.profileService = profileService;
        this.messages = messages;
        this.assetImages = assetImages;
        this.iconCatalog = iconCatalog;
    }

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("profile.description"))
                .addOption(OptionType.USER, USER_OPTION, messages.get("profile.option.user"), false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        User caller = event.getUser();
        OptionMapping option = event.getOption(USER_OPTION);
        User target = option == null ? caller : option.getAsUser();

        OptionalLong accountId = profileService.findLinkedAccountId(target.getIdLong());
        if (accountId.isEmpty()) {
            String text = target.getIdLong() == caller.getIdLong()
                    ? messages.get("profile.not_linked_self")
                    : messages.get("profile.not_linked_other", target.getAsMention());
            event.reply(text).setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        answer(event.getHook(), caller.getIdLong(), target, accountId.getAsLong());
    }

    // The reply is already deferred here, so CommandRouter cannot answer an exception: map every failure to a message.
    private void answer(InteractionHook hook, long callerId, User target, long accountId) {
        try {
            ProfileSummary summary = profileService.getProfile(accountId);
            hook.editOriginalEmbeds(embed(summary, target)).queue();
        } catch (NoMatchHistoryException ex) {
            hook.editOriginal(messages.get("profile.no_matches")).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "Profile", callerId, "accountId=" + accountId, ex);
        }
    }

    private MessageEmbed embed(ProfileSummary summary, User target) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(EmbedStyle.COLOR)
                .setAuthor(target.getEffectiveName(), null, target.getEffectiveAvatarUrl())
                .setTitle(messages.get("profile.title"));

        if (summary.rankTier() != null) {
            assetImages.rankBadgeUrl(summary.rankTier(), summary.subrank()).ifPresent(builder::setThumbnail);
        }

        builder.addField(messages.get("profile.field.rank"), rankValue(summary), false);
        builder.addField(messages.get("profile.field.recent", String.valueOf(summary.recentMatches())),
                recentValue(summary), false);
        builder.addField(messages.get("profile.field.top_heroes"), topHeroesValue(summary), false);
        builder.setFooter(messages.get("profile.footer", String.valueOf(summary.accountId())));

        return builder.build();
    }

    private String rankValue(ProfileSummary summary) {
        if (summary.rankTier() == null) {
            return messages.get("profile.rank_unranked");
        }
        String rank;
        if (summary.rankName() == null) {
            rank = messages.get("profile.rank_unknown_tier", String.valueOf(summary.rankTier()));
        } else if (summary.subrank() > 0) {
            rank = messages.get("profile.rank_value", summary.rankName(), String.valueOf(summary.subrank()));
        } else {
            rank = summary.rankName();
        }
        if (summary.rankPercentile() != null) {
            double topPercent = Math.max(0.0, Math.round((100.0 - summary.rankPercentile()) * 10.0) / 10.0);
            return rank + "\n" + messages.get("profile.percentile", CommandSupport.oneDecimal(topPercent));
        }
        return rank;
    }

    private String recentValue(ProfileSummary summary) {
        return messages.get("profile.recent_value",
                String.valueOf(summary.recentWins()),
                String.valueOf(summary.recentMatches()),
                String.valueOf(summary.winRatePercent()),
                CommandSupport.oneDecimal(summary.avgKills()),
                CommandSupport.oneDecimal(summary.avgDeaths()),
                CommandSupport.oneDecimal(summary.avgAssists()));
    }

    private String topHeroesValue(ProfileSummary summary) {
        if (summary.topHeroes().isEmpty()) {
            return messages.get("profile.no_recent_heroes");
        }
        List<String> lines = summary.topHeroes().stream()
                .map(this::heroLine)
                .toList();
        return EmbedStyle.fitField(lines);
    }

    private String heroLine(HeroUsage hero) {
        String heroName = hero.heroName() != null
                ? hero.heroName()
                : messages.get("profile.hero_unknown", String.valueOf(hero.heroId()));
        String line = messages.get("profile.hero_line", heroName, String.valueOf(hero.matches()));
        return EmbedStyle.withIcon(iconCatalog.hero(hero.heroId()), line);
    }
}
