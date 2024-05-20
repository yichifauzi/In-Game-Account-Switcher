/*
 * In-Game Account Switcher is a mod for Minecraft that allows you to change your logged in account in-game, without restarting Minecraft.
 * Copyright (C) 2015-2022 The_Fireplace
 * Copyright (C) 2021-2024 VidTu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>
 */

package ru.vidtu.ias.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.chat.NarratorChatListener;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.IAS;
import ru.vidtu.ias.IASMinecraft;
import ru.vidtu.ias.account.MicrosoftAccount;
import ru.vidtu.ias.auth.LoginData;
import ru.vidtu.ias.auth.handlers.LoginHandler;
import ru.vidtu.ias.config.IASConfig;
import ru.vidtu.ias.legacy.LastPassRenderCallback;
import ru.vidtu.ias.legacy.LegacyTooltip;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Login popup screen.
 *
 * @author VidTu
 */
final class LoginPopupScreen extends Screen implements LoginHandler, LastPassRenderCallback {
    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS/LoginPopupScreen");

    /**
     * Parent screen.
     */
    private final Screen parent;

    /**
     * Last pass callbacks list.
     */
    private final List<Runnable> lastPass = new LinkedList<>();

    /**
     * Synchronization lock.
     */
    private final Object lock = new Object();

    /**
     * Current stage.
     */
    @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized") // <- toString()
    private Component stage = new TranslatableComponent(MicrosoftAccount.INITIALIZING).withStyle(ChatFormatting.YELLOW);

    /**
     * Current stage label.
     */
    @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized") // <- toString()
    private MultiLineLabel label;

    /**
     * Password box.
     */
    private PopupBox password;

    /**
     * Password future.
     */
    private CompletableFuture<String> passFuture;

    /**
     * Crypt password tip.
     */
    private MultiLineLabel cryptPasswordTip;

    /**
     * Creates a new login screen.
     *
     * @param parent Parent screen
     */
    LoginPopupScreen(Screen parent) {
        super(new TranslatableComponent("ias.login"));
        this.parent = parent;
    }

    @Override
    public boolean cancelled() {
        // Bruh.
        assert this.minecraft != null;

        // Cancelled if no longer displayed.
        return this != this.minecraft.screen;
    }

    @Override
    protected void init() {
        // Bruh.
        assert this.minecraft != null;

        // Synchronize to prevent funny things.
        synchronized (this.lock) {
            // Unbake label.
            this.label = null;
        }

        // Init parent.
        if (this.parent != null) {
            this.parent.init(this.minecraft, this.width, this.height);
        }

        // Add cancel button.
        this.addRenderableWidget(new PopupButton(this.width / 2 - 75, this.height / 2 + 74 - 22, 150, 20,
                CommonComponents.GUI_CANCEL, btn -> this.onClose(), LegacyTooltip.EMPTY));

        // Add password box, if future exists.
        if (this.passFuture != null) {
            // Add password box.
            this.password = new PopupBox(this.font, this.width / 2 - 100, this.height / 2 - 10 + 5, 178, 20, this.password, new TranslatableComponent("ias.password"), () -> {
                // Prevent NPE, just in case.
                if (this.passFuture == null || this.password == null) return;
                String value = this.password.getValue();
                if (value.isBlank()) return;

                // Complete the future.
                this.passFuture.complete(value);
            }, true, new TranslatableComponent("ias.password.hint").withStyle(ChatFormatting.DARK_GRAY));
            this.password.setFormatter((s, i) -> IASConfig.passwordEchoing ? FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY) : FormattedCharSequence.EMPTY);
            this.password.setMaxLength(32);
            this.addRenderableWidget(this.password);

            // Add enter password button.
            PopupButton button = new PopupButton(this.width / 2 - 100 + 180, this.height / 2 - 10 + 5, 20, 20, new TextComponent(">>"), btn -> {
                // Prevent NPE, just in case.
                if (this.passFuture == null || this.password == null) return;
                String value = this.password.getValue();
                if (value.isBlank()) return;

                // Complete the future.
                this.passFuture.complete(value);
            }, LegacyTooltip.EMPTY);
            button.active = !this.password.getValue().isBlank();
            this.addRenderableWidget(button);
            this.password.setResponder(value -> button.active = !value.isBlank());

            // Create tip.
            this.cryptPasswordTip = MultiLineLabel.create(this.font, new TranslatableComponent("ias.password.tip"), 320);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.password == null) return;
        this.password.tick();
    }

    @Override
    public void onClose() {
        // Bruh.
        assert this.minecraft != null;

        // Complete password future with cancel, if any.
        if (this.passFuture != null) {
            this.passFuture.complete(null);
        }

        // Close to parent.
        this.minecraft.setScreen(this.parent);
    }

    @SuppressWarnings("NonPrivateFieldAccessedInSynchronizedContext") // <- Supertype.
    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        // Bruh.
        assert this.minecraft != null;

        // Render parent behind.
        if (this.parent != null) {
            pose.pushPose();
            pose.translate(0.0F, 0.0F, -500.0F);
            this.parent.render(pose, 0, 0, delta);
            pose.popPose();
        }

        // Render background and widgets.
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, delta);

        // Render the title.
        pose.pushPose();
        pose.scale(2.0F, 2.0F, 2.0F);
        drawCenteredString(pose, this.font, this.title, this.width / 4, this.height / 4 - 74 / 2, 0xFF_FF_FF_FF);
        pose.popPose();

        // Render password OR label.
        if (this.passFuture != null && this.password != null && this.cryptPasswordTip != null) {
            drawCenteredString(pose, this.font, this.password.getMessage(), this.width / 2, this.height / 2 - 10 - 5, 0xFF_FF_FF_FF);
            pose.pushPose();
            pose.scale(0.5F, 0.5F, 0.5F);
            this.cryptPasswordTip.renderCentered(pose, this.width, this.height + 40, 10, 0xFF_FF_FF_00);
            pose.popPose();
        } else {
            // Synchronize to prevent funny things.
            synchronized (this.lock) {
                // Label is unbaked.
                if (this.label == null) {
                    // Get the component.
                    Component component = Objects.requireNonNullElse(this.stage, TextComponent.EMPTY);

                    // Bake the label.
                    this.label = MultiLineLabel.create(this.font, component, 240);

                    // Narrate.
                    NarratorChatListener.INSTANCE.handle(ChatType.SYSTEM, component, Util.NIL_UUID);
                }

                // Render the label.
                this.label.renderCentered(pose, this.width / 2, (this.height - this.label.getLineCount() * 9) / 2 - 4, 9, 0xFF_FF_FF_FF);
            }
        }

        // Last pass.
        for (Runnable callback : this.lastPass) {
            callback.run();
        }
        this.lastPass.clear();
    }

    @Override
    public void renderBackground(PoseStack pose) {
        // Bruh.
        assert this.minecraft != null;

        // Render transparent background if parent exists.
        if (this.parent != null) {
            // Render gradient.
            fill(pose, 0, 0, this.width, this.height, 0x80_00_00_00);
        } else {
            super.renderBackground(pose);
        }

        // Render "form".
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        fill(pose, centerX - 125, centerY - 75, centerX + 125, centerY + 75, 0xF8_20_20_30);
        fill(pose, centerX - 124, centerY - 76, centerX + 124, centerY - 75, 0xF8_20_20_30);
        fill(pose, centerX - 124, centerY + 75, centerX + 124, centerY + 76, 0xF8_20_20_30);
    }

    @Override
    public void stage(String stage, Object... args) {
        // Bruh.
        assert this.minecraft != null;

        // Skip if not current screen.
        if (this != this.minecraft.screen) return;

        // Flush the stage.
        Component component = new TranslatableComponent(stage, args).withStyle(ChatFormatting.YELLOW);
        synchronized (this.lock) {
            this.stage = component;
            this.label = null;
        }
    }

    @Override
    public CompletableFuture<String> password() {
        // Bruh.
        assert this.minecraft != null;

        // Return current future if exists.
        if (this.passFuture != null) return this.passFuture;

        // Create a new future.
        this.passFuture = new CompletableFuture<>();

        // Inject into pass future.
        this.passFuture.thenAcceptAsync(password -> {
            // Remove future on completion.
            this.passFuture = null;
            this.password = null;
            this.cryptPasswordTip = null;

            // Redraw.
            this.init(this.minecraft, this.width, this.height);
        }, this.minecraft);

        // Schedule rerender.
        this.minecraft.execute(() -> this.init(this.minecraft, this.width, this.height));

        // Return created future.
        return this.passFuture;
    }

    @Override
    public void success(LoginData data, boolean changed) {
        // Bruh.
        assert this.minecraft != null;

        // Skip if not current screen.
        if (this != this.minecraft.screen) return;

        // User cancelled.
        if (data == null) {
            // Schedule on main.
            this.minecraft.execute(() -> {
                // Skip if not current screen.
                if (this != this.minecraft.screen) return;

                // Back to parent screen.
                this.minecraft.setScreen(this.parent);
            });

            // Don't log in.
            return;
        }

        // Log in.
        this.stage(MicrosoftAccount.SERVICES);

        // Save storage.
        if (changed) {
            try {
                IAS.disclaimersStorage();
                IAS.saveStorage();
            } catch (Throwable t) {
                LOGGER.error("IAS: Unable to save storage.", t);
            }
        }

        IASMinecraft.account(this.minecraft, data).thenRunAsync(() -> {
            // Skip if not current screen.
            if (this != this.minecraft.screen) return;

            // Back to parent screen.
            this.minecraft.setScreen(this.parent);
        }, this.minecraft).exceptionally(ex -> {
            // Handle error on error.
            this.error(new RuntimeException("Unable to change account.", ex));

            // Nothing...
            return null;
        });
    }

    @Override
    public void error(Throwable error) {
        // Bruh.
        assert this.minecraft != null;

        // Log it.
        LOGGER.error("IAS: Login error.", error);

        // Skip if not current screen.
        if (this != this.minecraft.screen) return;

        // Flush the stage.
        FriendlyException probable = FriendlyException.friendlyInChain(error);
        String key = probable != null ? probable.key() : "ias.error";
        Component component = new TranslatableComponent(key).withStyle(ChatFormatting.RED);
        synchronized (this.lock) {
            this.stage = component;
            this.label = null;
        }
    }

    @Override
    public void lastPass(@NotNull Runnable callback) {
        this.lastPass.add(callback);
    }

    @Override
    public String toString() {
        return "LoginPopupScreen{" +
                "stage=" + this.stage +
                ", label=" + this.label +
                '}';
    }
}
