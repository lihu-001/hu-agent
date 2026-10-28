package dev.huagent.command;

import dev.huagent.model.ModelSelection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /model 的候选模型：当前模型、配置的常用模型（models）与接口 /models 返回的模型。
 * 接口列表在 prefetch 时于后台查询并缓存，补全列表只读缓存、从不等待；查看列表或保存默认时才等待结果，
 * 上次查询失败的会重新查询。
 */
final class ModelCatalog {
    private final ModelSelection selection;
    private final List<String> favorites;
    private final AtomicReference<CompletableFuture<List<String>>> listing = new AtomicReference<>();

    ModelCatalog(ModelSelection selection, List<String> favorites) {
        this.selection = Objects.requireNonNull(selection);
        this.favorites = List.copyOf(favorites);
    }

    ModelSelection selection() {
        return selection;
    }

    List<String> favorites() {
        return favorites;
    }

    /** 后台查询接口模型列表，不等待；已经查询过（含进行中）时什么都不做。 */
    void prefetch() {
        fetch(false);
    }

    /** 接口提供的模型，按名称排序；还没有成功的结果时等待查询（上次失败则重新查询）。 */
    List<String> available() throws IOException, InterruptedException {
        try {
            return fetch(true).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IOException(e.getCause().getClass().getSimpleName(), e.getCause());
        }
    }

    /** 已查到的接口模型，不等待；还没查到或查询失败时为空。 */
    List<String> cached() {
        CompletableFuture<List<String>> current = listing.get();
        return current != null && current.isDone() && !current.isCompletedExceptionally() ? current.join() : List.of();
    }

    /** 补全列表：当前模型在最前，其次是常用模型，最后是接口模型，重复的只列一次。 */
    List<SlashCommand.Choice> choices() {
        List<SlashCommand.Choice> choices = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        String current = selection.model();
        listed.add(current);
        choices.add(new SlashCommand.Choice(current, favorites.contains(current) ? "当前 · 常用" : "当前"));
        for (String favorite : favorites) {
            if (listed.add(favorite)) {
                choices.add(new SlashCommand.Choice(favorite, "常用"));
            }
        }
        for (String model : cached()) {
            if (listed.add(model)) {
                choices.add(new SlashCommand.Choice(model, ""));
            }
        }
        return choices;
    }

    private CompletableFuture<List<String>> fetch(boolean retryFailed) {
        while (true) {
            CompletableFuture<List<String>> current = listing.get();
            if (current != null && !(retryFailed && current.isCompletedExceptionally())) {
                return current;
            }
            CompletableFuture<List<String>> next = new CompletableFuture<>();
            if (listing.compareAndSet(current, next)) {
                Thread.ofVirtual().name("hu-model-list").start(() -> {
                    try {
                        List<String> models = new ArrayList<>(selection.listModels());
                        models.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));
                        next.complete(List.copyOf(models));
                    } catch (Exception e) {
                        next.completeExceptionally(e);
                    }
                });
                return next;
            }
        }
    }
}
