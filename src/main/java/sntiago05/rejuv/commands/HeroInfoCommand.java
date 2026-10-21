package sntiago05.rejuv.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sntiago05.rejuv.clients.DeadlockApiUnavailableException;
import sntiago05.rejuv.domain.HeroInfo;
import sntiago05.rejuv.services.HeroInfoService;
import sntiago05.rejuv.services.HeroNotFoundException;
import sntiago05.rejuv.services.Messages;

@Component
public class HeroInfoCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(HeroInfoCommand.class);
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** Room kept for the footer, which is added after the fields. */
    private static final int FOOTER_RESERVE = 120;

    private final HeroInfoService heroInfoService;
    private final Messages messages;

    public HeroInfoCommand(HeroInfoService heroInfoService, Messages messages) {
        this.heroInfoService = heroInfoService;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "heroinfo";
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash(name(), messages.get("heroinfo.description"))
                .addOptions(CommandSupport.heroOption(messages.get("heroinfo.option.hero")));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        answer(event.getHook(), event.getUser().getIdLong(), CommandSupport.getHeroInput(event));
    }

    private void answer(InteractionHook hook, long callerId, String heroInput) {
        try {
            hook.editOriginalEmbeds(buildEmbed(heroInfoService.getInfo(heroInput))).queue();
        } catch (HeroNotFoundException ex) {
            hook.editOriginal(messages.get("heroinfo.hero_not_found")).queue();
        } catch (DeadlockApiUnavailableException ex) {
            hook.editOriginal(messages.get("error.data_source_busy")).queue();
        } catch (RuntimeException ex) {
            CommandSupport.handleUnexpectedError(hook, messages, log, "HeroInfo", callerId,
                    "heroInput=" + heroInput, ex);
        }
    }

    private MessageEmbed buildEmbed(HeroInfo info) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(embedColor(info.colorHex()))
                .setTitle(AnalyzeCommand.truncate(info.name(), AnalyzeCommand.MAX_TITLE_LENGTH));
        if (info.iconUrl() != null) {
            builder.setThumbnail(info.iconUrl());
        }

        List<String> description = new ArrayList<>(2);
        if (info.role() != null) {
            description.add(info.role());
        }
        if (!info.playable()) {
            description.add(messages.get("heroinfo.not_playable"));
        }
        if (!description.isEmpty()) {
            builder.setDescription(AnalyzeCommand.truncate(String.join("\n", description),
                    AnalyzeCommand.MAX_FIELD_LENGTH));
        }

        addIfFits(builder, messages.get("heroinfo.field.type"), typeLine(info));
        addIfFits(builder, messages.get("heroinfo.field.playstyle"), info.playstyle());
        addIfFits(builder, messages.get("heroinfo.field.tags"),
                info.tags().isEmpty() ? null : String.join(", ", info.tags()));
        List<String> statLines = new ArrayList<>(info.stats().size());
        for (HeroInfo.Stat stat : info.stats()) {
            statLines.add(messages.get("heroinfo.stat.line", messages.get("heroinfo.stat." + stat.key()),
                    formatNumber(stat.value())));
        }
        addIfFits(builder, messages.get("heroinfo.field.stats"), EmbedStyle.fitField(statLines));
        addIfFits(builder, messages.get("heroinfo.field.lore"), info.lore());

        builder.setFooter(AnalyzeCommand.truncate(messages.get("heroinfo.footer"), AnalyzeCommand.MAX_FOOTER_LENGTH));
        return builder.build();
    }

    /**
     * Adds a field cut to 1024 characters, and only when the whole embed stays within Discord's 6000-character
     * limit (later, less important fields are the ones dropped). Blank values add nothing.
     */
    private void addIfFits(EmbedBuilder builder, String name, String value) {
        if (value == null || value.isBlank() || builder.getFields().size() >= MessageEmbed.MAX_FIELD_AMOUNT) {
            return;
        }
        String cut = AnalyzeCommand.truncate(value, AnalyzeCommand.MAX_FIELD_LENGTH);
        if (builder.length() + name.length() + cut.length() + FOOTER_RESERVE <= MessageEmbed.EMBED_MAX_LENGTH_BOT) {
            builder.addField(name, cut, false);
        }
    }

    private String typeLine(HeroInfo info) {
        List<String> parts = new ArrayList<>(3);
        if (info.heroType() != null) {
            parts.add(messages.get("heroinfo.type.hero_type", capitalize(info.heroType())));
        }
        if (info.complexity() != null) {
            parts.add(messages.get("heroinfo.type.complexity", String.valueOf(info.complexity())));
        }
        if (info.gunTag() != null) {
            parts.add(messages.get("heroinfo.type.gun", info.gunTag()));
        }
        return String.join(", ", parts);
    }

    /** The hero color when it is a valid #RRGGBB value, else the brand color. */
    static int embedColor(String hex) {
        if (hex == null || !HEX_COLOR.matcher(hex).matches()) {
            return EmbedStyle.COLOR;
        }
        return Integer.parseInt(hex.substring(1), 16);
    }

    /** At most one decimal, no trailing ".0": 830 stays "830", 6.7 stays "6.7", 2.0 becomes "2". */
    static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
