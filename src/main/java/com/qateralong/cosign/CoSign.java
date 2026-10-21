package com.qateralong.cosign;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

public class CoSign implements ModInitializer {
    private static final String KEY = "cosign";
    private static final int PER_PAGE = 12;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final Map<UUID, Lectern> LECTERNS = new ConcurrentHashMap<>();

    private record Lectern(Level level, BlockPos pos) {
    }

    private record Found(ItemStack stack, InteractionHand hand, LecternBlockEntity lectern) {
    }

    private record Signature(String name, String uuid, long time) {
    }

    @Override
    public void onInitialize() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() && level.getBlockState(hit.getBlockPos()).is(Blocks.LECTERN)) {
                LECTERNS.put(player.getUUID(), new Lectern(level, hit.getBlockPos().immutable()));
            }
            return InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : List.of("contract", "договор")) {
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .executes(context -> create(context, ""))
                    .then(Commands.literal("sign").then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> sign(context, StringArgumentType.getString(context, "id")))))
                    .then(Commands.literal("finish").then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> finish(context, StringArgumentType.getString(context, "id")))))
                    .then(Commands.literal("edit").then(Commands.argument("id", StringArgumentType.word())
                            .executes(context -> edit(context, StringArgumentType.getString(context, "id")))))
                    .then(Commands.literal("title").then(Commands.argument("title", StringArgumentType.greedyString())
                            .executes(context -> create(context, StringArgumentType.getString(context, "title")))));
            dispatcher.register(node);
        }
    }

    private static int create(CommandContext<CommandSourceStack> context, String title) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(Items.WRITABLE_BOOK)) {
            context.getSource().sendFailure(Component.literal("Возьмите в руку книгу с пером с текстом договора и введите /contract "
                    + "(или /contract title <название>)"));
            return 0;
        }
        WritableBookContent content = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        List<String> pages = content.pages().stream().map(Filterable::raw).toList();
        if (pages.stream().allMatch(String::isBlank)) {
            context.getSource().sendFailure(Component.literal("Книга пустая — сначала напишите текст"));
            return 0;
        }
        String name = title.isBlank() ? "Договор" : title.strip();
        if (name.length() > WrittenBookContent.TITLE_MAX_LENGTH) {
            name = name.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
        }
        CompoundTag data = new CompoundTag();
        data.putString("id", UUID.randomUUID().toString().substring(0, 8));
        data.putString("title", name);
        data.putString("author", player.getGameProfile().getName());
        data.putString("authorId", player.getUUID().toString());
        ListTag text = new ListTag();
        pages.forEach(page -> text.add(StringTag.valueOf(page)));
        data.put("text", text);
        data.put("sig", new ListTag());
        ItemStack book = build(data, stack.getCount());
        player.setItemInHand(InteractionHand.MAIN_HAND, book);
        String shown = name;
        context.getSource().sendSuccess(() -> Component.literal("Договор «" + shown + "» готов к подписям. Откройте книгу — на первой странице "
                + "кнопки «Подписать» и «Завершить». Можно положить на кафедру.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static CompoundTag data(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return null;
        }
        CompoundTag tag = custom.copyTag();
        return tag.contains(KEY, Tag.TAG_COMPOUND) ? tag.getCompound(KEY) : null;
    }

    private static List<Signature> signatures(CompoundTag data) {
        List<Signature> list = new ArrayList<>();
        for (Tag raw : data.getList("sig", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            list.add(new Signature(entry.getString("n"), entry.getString("u"), entry.getLong("t")));
        }
        return list;
    }

    private static List<String> text(CompoundTag data) {
        List<String> list = new ArrayList<>();
        for (Tag raw : data.getList("text", Tag.TAG_STRING)) {
            list.add(raw.getAsString());
        }
        return list;
    }

    private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
        return Component.literal(text).withStyle(style -> style.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private static List<Filterable<Component>> signaturePages(List<Signature> signatures) {
        List<Filterable<Component>> pages = new ArrayList<>();
        if (signatures.isEmpty()) {
            pages.add(Filterable.passThrough(Component.literal("Подписи\n\n").withStyle(ChatFormatting.BOLD)
                    .append(Component.literal("Пока никто не подписал").withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GRAY)))));
            return pages;
        }
        for (int start = 0; start < signatures.size(); start += PER_PAGE) {
            MutableComponent page = Component.literal(start == 0 ? "Подписи (" + signatures.size() + ")\n\n" : "Подписи (продолжение)\n\n")
                    .withStyle(ChatFormatting.BOLD);
            for (int i = start; i < Math.min(signatures.size(), start + PER_PAGE); i++) {
                Signature signature = signatures.get(i);
                String date = DATE.format(Instant.ofEpochMilli(signature.time()));
                page.append(Component.literal((i + 1) + ". " + signature.name() + "\n").withStyle(style -> style.withBold(false)
                        .withColor(ChatFormatting.BLACK).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Подписано " + date)))));
            }
            pages.add(Filterable.passThrough(page));
        }
        return pages;
    }

    private static ItemStack build(CompoundTag data, int count) {
        String id = data.getString("id");
        String title = data.getString("title");
        String author = data.getString("author");
        List<Signature> signatures = signatures(data);
        List<Filterable<Component>> pages = new ArrayList<>();
        MutableComponent cover = Component.literal(title + "\n").withStyle(ChatFormatting.BOLD)
                .append(Component.literal("автор: " + author + "\n\n").withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GRAY)))
                .append(Component.literal("Подписей: " + signatures.size() + "\n").withStyle(style -> style.withBold(false).withColor(ChatFormatting.BLACK)))
                .append(Component.literal("Текст — на следующих страницах, список подписей — в конце.\n\n")
                        .withStyle(style -> style.withBold(false).withColor(ChatFormatting.DARK_GRAY)))
                .append(button("[✍ Подписать]", ChatFormatting.DARK_GREEN, "/contract sign " + id, "Поставить свою подпись"))
                .append(Component.literal("\n\n"))
                .append(button("[✔ Завершить]", ChatFormatting.DARK_RED, "/contract finish " + id,
                        "Только автор (" + author + "): закрыть сбор подписей и запечатать книгу"));
        if (signatures.isEmpty()) {
            cover.append(Component.literal("\n")).append(button("[✎ Изменить текст]", ChatFormatting.DARK_GRAY, "/contract edit " + id,
                    "Только автор и только пока нет подписей: вернуть книгу с пером"));
        } else {
            cover.append(Component.literal("\n\nПодпись нельзя отозвать, текст закреплён").withStyle(style -> style.withBold(false)
                    .withColor(ChatFormatting.DARK_GRAY)));
        }
        pages.add(Filterable.passThrough(cover));
        for (String page : text(data)) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        pages.addAll(signaturePages(signatures));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK, count);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), author, 0, pages, true));
        CompoundTag custom = new CompoundTag();
        custom.put(KEY, data);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        book.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Собирает подписи: " + signatures.size())
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withItalic(false)))));
        return book;
    }

    private static Found find(ServerPlayer player, String id) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            CompoundTag data = data(stack);
            if (data != null && data.getString("id").equals(id)) {
                return new Found(stack, hand, null);
            }
        }
        Lectern lectern = LECTERNS.get(player.getUUID());
        if (lectern != null && lectern.level() == player.level() && lectern.pos().closerToCenterThan(player.position(), 8.0)
                && lectern.level().getBlockEntity(lectern.pos()) instanceof LecternBlockEntity entity && entity.hasBook()) {
            CompoundTag data = data(entity.getBook());
            if (data != null && data.getString("id").equals(id)) {
                return new Found(entity.getBook(), null, entity);
            }
        }
        return null;
    }

    private static void replace(ServerPlayer player, Found found, ItemStack book, boolean reopen) {
        if (found.lectern() != null) {
            found.lectern().setBook(book);
            found.lectern().setChanged();
            if (reopen) {
                player.openMenu(found.lectern());
            }
        } else {
            player.setItemInHand(found.hand(), book);
            if (reopen) {
                player.connection.send(new ClientboundOpenBookPacket(found.hand()));
            }
        }
    }

    private static Found require(CommandContext<CommandSourceStack> context, String id) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Found found = find(player, id);
        if (found == null) {
            context.getSource().sendFailure(Component.literal("Книга не найдена: держите её в руке или откройте на кафедре"));
        }
        return found;
    }

    private static boolean isAuthor(CommandSourceStack source, ServerPlayer player, CompoundTag data) {
        return data.getString("authorId").equals(player.getUUID().toString()) || source.hasPermission(2);
    }

    private static int sign(CommandContext<CommandSourceStack> context, String id) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Found found = require(context, id);
        if (found == null) {
            return 0;
        }
        CompoundTag data = data(found.stack());
        String uuid = player.getUUID().toString();
        if (signatures(data).stream().anyMatch(signature -> signature.uuid().equals(uuid))) {
            context.getSource().sendFailure(Component.literal("Вы уже подписали этот договор — подпись нельзя ни поставить дважды, ни отозвать"));
            return 0;
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("n", player.getGameProfile().getName());
        entry.putString("u", uuid);
        entry.putLong("t", System.currentTimeMillis());
        data.getList("sig", Tag.TAG_COMPOUND).add(entry);
        int total = data.getList("sig", Tag.TAG_COMPOUND).size();
        replace(player, found, build(data, found.stack().getCount()), true);
        String title = data.getString("title");
        context.getSource().sendSuccess(() -> Component.literal("Вы подписали «" + title + "» (подписей: " + total + ")").withStyle(ChatFormatting.GREEN), false);
        ServerPlayer author = player.server.getPlayerList().getPlayer(UUID.fromString(data.getString("authorId")));
        if (author != null && author != player) {
            author.sendSystemMessage(Component.literal(player.getGameProfile().getName() + " подписал ваш договор «" + title + "» (подписей: " + total + ")")
                    .withStyle(ChatFormatting.GOLD));
        }
        return total;
    }

    private static int finish(CommandContext<CommandSourceStack> context, String id) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Found found = require(context, id);
        if (found == null) {
            return 0;
        }
        CompoundTag data = data(found.stack());
        if (!isAuthor(context.getSource(), player, data)) {
            context.getSource().sendFailure(Component.literal("Завершить сбор подписей может только автор — " + data.getString("author")));
            return 0;
        }
        List<Signature> signatures = signatures(data);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String page : text(data)) {
            pages.add(Filterable.passThrough(Component.literal(page)));
        }
        pages.addAll(signaturePages(signatures));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK, found.stack().getCount());
        String title = data.getString("title");
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), data.getString("author"), 0, pages, true));
        replace(player, found, book, false);
        context.getSource().sendSuccess(() -> Component.literal("«" + title + "» запечатан, подписей: " + signatures.size()).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int edit(CommandContext<CommandSourceStack> context, String id) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Found found = require(context, id);
        if (found == null) {
            return 0;
        }
        CompoundTag data = data(found.stack());
        if (!isAuthor(context.getSource(), player, data)) {
            context.getSource().sendFailure(Component.literal("Менять текст может только автор — " + data.getString("author")));
            return 0;
        }
        if (!data.getList("sig", Tag.TAG_COMPOUND).isEmpty()) {
            context.getSource().sendFailure(Component.literal("Текст менять нельзя: договор уже подписали, подписи не отзываются"));
            return 0;
        }
        if (found.lectern() != null) {
            context.getSource().sendFailure(Component.literal("Сначала заберите книгу с кафедры"));
            return 0;
        }
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK, found.stack().getCount());
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(text(data).stream().map(Filterable::passThrough).toList()));
        replace(player, found, book, false);
        context.getSource().sendSuccess(() -> Component.literal("Книга снова редактируется. Когда закончите — /contract")
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }
}
