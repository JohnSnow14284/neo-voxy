package me.cortex.voxy.client.config;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.FlagHook;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

public class SodiumConfigBuilder {
   private static <F, T> T[] map(F[] from, Function<F, T> mapper, Function<Integer, T[]> factory) {
      T[] arr = (T[])factory.apply(from.length);

      for (int i = 0; i < from.length; i++) {
         arr[i] = mapper.apply(from[i]);
      }

      return arr;
   }

   private static Identifier[] mapIds(String[] strings) {
      return map(strings, Identifier::parse, x$0 -> new Identifier[x$0]);
   }

   public static void buildToSodium(
      ConfigBuilder builder,
      ModOptionsBuilder options,
      StorageEventHandler saveHandler,
      Consumer<SodiumConfigBuilder.PostApplyOps> registerOps,
      SodiumConfigBuilder.Page... pages
   ) {
      SodiumConfigBuilder.BuildCtx ctx = new SodiumConfigBuilder.BuildCtx();
      registerOps.accept(ctx.postRunner);
      ctx.saveHandler = saveHandler;

      for (SodiumConfigBuilder.Page page : pages) {
         options.addPage(page.create(builder, ctx));
      }

      options.registerFlagHook(ctx.postRunner.build());
   }

   public static class BoolOption extends SodiumConfigBuilder.Option<Boolean, SodiumConfigBuilder.BoolOption, BooleanOptionBuilder> {
      public BoolOption(String id, Component name, Component tooltip, Supplier<Boolean> getter, Consumer<Boolean> setter) {
         super(id, name, tooltip, getter, setter);
      }

      public BoolOption(String id, Component name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
         super(id, name, getter, setter);
      }

      protected BooleanOptionBuilder createType(ConfigBuilder builder) {
         return builder.createBooleanOption(Identifier.parse(this.id));
      }
   }

   private static final class BuildCtx {
      public SodiumConfigBuilder.PostApplyOps postRunner = new SodiumConfigBuilder.PostApplyOps();
      public StorageEventHandler saveHandler;
   }

   public abstract static class Enableable<TYPE extends SodiumConfigBuilder.Enableable<TYPE>> {
      @Nullable
      private SodiumConfigBuilder.Enabler prevEnabler;
      @Nullable
      protected SodiumConfigBuilder.Enabler enabler;

      private TYPE setEnabler0(SodiumConfigBuilder.Enabler enabler) {
         this.prevEnabler = this.enabler;
         this.enabler = enabler;
         this.updateChildren();
         return (TYPE)this;
      }

      private void updateChildren() {
         SodiumConfigBuilder.Enableable[] children = this.getEnablerChildren();
         if (children != null) {
            for (SodiumConfigBuilder.Enableable child : children) {
               child.parentEnablerUpdate(this);
            }
         }
      }

      private TYPE parentEnablerUpdate(SodiumConfigBuilder.Enableable parent) {
         if (this.enabler == null) {
            this.setEnabler0(parent.enabler);
         } else if (this.enabler == parent.prevEnabler) {
            this.setEnabler0(parent.enabler);
         } else if (this.enabler.inheritedEnabler != null && this.enabler.inheritedEnabler == parent.prevEnabler) {
            this.setEnabler0(this.enabler.baseEnabler.joinAnd(parent.enabler));
         } else if (this.enabler.inheritedEnabler == null && this.enabler.joinParent) {
            this.setEnabler0(this.enabler.joinAnd(parent.enabler));
         }

         return (TYPE)this;
      }

      public TYPE setEnabler(Predicate<ConfigState> enabler, String... dependencies) {
         return this.setEnabler0(new SodiumConfigBuilder.Enabler(enabler, dependencies, false));
      }

      public TYPE setEnablerInherit(Predicate<ConfigState> enabler, String... dependencies) {
         return this.setEnabler0(new SodiumConfigBuilder.Enabler(enabler, dependencies, true));
      }

      public TYPE setEnablerInherit(Predicate<ConfigState> enabler, Identifier... dependencies) {
         return this.setEnabler0(new SodiumConfigBuilder.Enabler(enabler, dependencies, true));
      }

      public TYPE setEnabler(String enabler) {
         if (enabler == null) {
            return this.setEnabler(s -> true);
         } else {
            Identifier id = Identifier.parse(enabler);
            return this.setEnabler(s -> s.readBooleanOption(id), enabler);
         }
      }

      public TYPE setEnablerAND(String... enablers) {
         Identifier[] enablersId = SodiumConfigBuilder.mapIds(enablers);
         return this.setEnabler0(new SodiumConfigBuilder.Enabler(s -> {
            for (Identifier id : enablersId) {
               if (!s.readBooleanOption(id)) {
                  return false;
               }
            }

            return true;
         }, enablersId));
      }

      protected SodiumConfigBuilder.Enableable[] getEnablerChildren() {
         return null;
      }
   }

   private static class Enabler {
      public final Predicate<ConfigState> tester;
      public final Identifier[] dependencies;
      public final boolean joinParent;
      public SodiumConfigBuilder.Enabler inheritedEnabler;
      public SodiumConfigBuilder.Enabler baseEnabler;

      public Enabler(Predicate<ConfigState> tester, Identifier[] dependencies, boolean joinParent) {
         this.tester = tester;
         this.dependencies = dependencies;
         this.joinParent = joinParent;
      }

      public Enabler(Predicate<ConfigState> tester, String[] dependencies) {
         this(tester, dependencies, false);
      }

      public Enabler(Predicate<ConfigState> tester, Identifier[] dependencies) {
         this(tester, dependencies, false);
      }

      public Enabler(Predicate<ConfigState> tester, String[] dependencies, boolean joinParent) {
         this(tester, SodiumConfigBuilder.mapIds(dependencies), joinParent);
      }

      public SodiumConfigBuilder.Enabler joinAnd(SodiumConfigBuilder.Enabler parent) {
         Set<Identifier> identifiers = new HashSet<>();

         for (Identifier i : this.dependencies) {
            identifiers.add(i);
         }

         for (Identifier i : parent.dependencies) {
            identifiers.add(i);
         }

         Predicate<ConfigState> tester = state -> !this.tester.test(state) ? false : parent.tester.test(state);
         SodiumConfigBuilder.Enabler newEnabler = new SodiumConfigBuilder.Enabler(tester, identifiers.toArray(Identifier[]::new));
         newEnabler.baseEnabler = this;
         newEnabler.inheritedEnabler = parent;
         return newEnabler;
      }
   }

   public static class EnumOption<T extends Enum<T>> extends SodiumConfigBuilder.Option<T, SodiumConfigBuilder.EnumOption<T>, EnumOptionBuilder<T>> {
      private final Class<T> theEnum;
      private Function<T, Component> nameProvider = value -> Component.literal(value == null ? "NULL" : value.toString());

      public EnumOption(String id, Class<T> theEnum, Component name, Component tooltip, Supplier<T> getter, Consumer<T> setter) {
         super(id, name, tooltip, getter, setter);
         this.theEnum = theEnum;
      }

      public EnumOption(String id, Class<T> theEnum, Component name, Supplier<T> getter, Consumer<T> setter) {
         super(id, name, getter, setter);
         this.theEnum = theEnum;
      }

      public SodiumConfigBuilder.EnumOption<T> setNameProvider(Function<T, Component> provider) {
         this.nameProvider = provider;
         return this;
      }

      protected EnumOptionBuilder<T> createType(ConfigBuilder builder) {
         return builder.createEnumOption(Identifier.parse(this.id), this.theEnum);
      }

      protected EnumOptionBuilder<T> create(ConfigBuilder builder, SodiumConfigBuilder.BuildCtx ctx) {
         EnumOptionBuilder<T> option = (EnumOptionBuilder<T>)super.create(builder, ctx);
         option.setElementNameProvider(this.nameProvider);
         return option;
      }
   }

   public static class Group extends SodiumConfigBuilder.Enableable<SodiumConfigBuilder.Group> {
      protected SodiumConfigBuilder.Option[] options;

      public Group(SodiumConfigBuilder.Option... options) {
         this.options = options;
      }

      protected OptionGroupBuilder create(ConfigBuilder builder, SodiumConfigBuilder.BuildCtx ctx) {
         OptionGroupBuilder group = builder.createOptionGroup();

         for (SodiumConfigBuilder.Option option : this.options) {
            group.addOption(option.create(builder, ctx));
         }

         return group;
      }

      @Override
      protected SodiumConfigBuilder.Enableable[] getEnablerChildren() {
         return this.options;
      }
   }

   public static class IntOption extends SodiumConfigBuilder.Option<Integer, SodiumConfigBuilder.IntOption, IntegerOptionBuilder> {
      protected Function<ConfigState, Range> rangeProvider;
      protected String[] rangeDependencies;
      protected ControlValueFormatter formatter = v -> Component.literal(Integer.toString(v));

      public IntOption(String id, Component name, Component tooltip, Supplier<Integer> getter, Consumer<Integer> setter, Range range) {
         super(id, name, tooltip, getter, setter);
         this.rangeProvider = s -> range;
      }

      public IntOption(String id, Component name, Supplier<Integer> getter, Consumer<Integer> setter, Range range) {
         super(id, name, getter, setter);
         this.rangeProvider = s -> range;
      }

      public SodiumConfigBuilder.IntOption setFormatter(IntFunction<Component> formatter) {
         this.formatter = v -> formatter.apply(v);
         return this;
      }

      protected IntegerOptionBuilder createType(ConfigBuilder builder) {
         return builder.createIntegerOption(Identifier.parse(this.id));
      }

      protected IntegerOptionBuilder create(ConfigBuilder builder, SodiumConfigBuilder.BuildCtx ctx) {
         IntegerOptionBuilder option = (IntegerOptionBuilder)super.create(builder, ctx);
         if (this.rangeDependencies != null && this.rangeDependencies.length != 0) {
            option.setRangeProvider(this.rangeProvider, SodiumConfigBuilder.mapIds(this.rangeDependencies));
         } else {
            option.setRange(this.rangeProvider.apply(null));
         }

         option.setValueFormatter(this.formatter);
         return option;
      }
   }

   public abstract static class Option<TYPE, OPTION extends SodiumConfigBuilder.Option<TYPE, OPTION, STYPE>, STYPE extends StatefulOptionBuilder<TYPE>>
      extends SodiumConfigBuilder.Enableable<SodiumConfigBuilder.Option<TYPE, OPTION, STYPE>> {
      protected String id;
      protected Component name;
      protected Component tooltip;
      protected Function<TYPE, Component> tooltipSupplier;
      protected Supplier<TYPE> getter;
      protected Consumer<TYPE> setter;
      protected OptionImpact impact;
      protected Consumer<TYPE> postRunner;
      protected Identifier[] postRunnerConflicts;
      protected Identifier[] postChangeFlags;

      public Option(String id, Component name, Component tooltip, Supplier<TYPE> getter, Consumer<TYPE> setter) {
         this.id = id;
         this.name = name;
         this.tooltip = tooltip;
         this.getter = getter;
         this.setter = setter;
      }

      public Option(String id, Component name, Supplier<TYPE> getter, Consumer<TYPE> setter) {
         this.id = id;
         this.name = name;
         this.getter = getter;
         this.setter = setter;
         if (name.getContents() instanceof TranslatableContents tc) {
            this.tooltip = Component.translatable(tc.getKey() + ".tooltip");
         } else {
            this.tooltip = name;
         }
      }

      public OPTION setTooltipSupplier(Function<TYPE, Component> supplier) {
         this.tooltipSupplier = supplier;
         return (OPTION)this;
      }

      public OPTION setImpact(OptionImpact impact) {
         this.impact = impact;
         return (OPTION)this;
      }

      public OPTION setPostChangeRunner(Consumer<TYPE> postRunner, String... dontRunIfChangedVars) {
         this.postRunner = postRunner;
         this.postRunnerConflicts = SodiumConfigBuilder.mapIds(dontRunIfChangedVars);
         return (OPTION)this;
      }

      public OPTION setPostChangeFlags(String... flags) {
         this.postChangeFlags = SodiumConfigBuilder.mapIds(flags);
         return (OPTION)this;
      }

      protected abstract STYPE createType(ConfigBuilder var1);

      protected STYPE create(ConfigBuilder builder, SodiumConfigBuilder.BuildCtx ctx) {
         STYPE option = this.createType(builder);
         option.setName(this.name);
         option.setTooltip(this.tooltip);
         Set<Identifier> flags = new LinkedHashSet<>();
         if (this.postRunner != null) {
            Identifier id = Identifier.parse(this.id);
            Consumer<TYPE> runner = this.postRunner;
            Supplier<TYPE> getter = this.getter;
            ctx.postRunner.register(id, () -> runner.accept(getter.get()), this.postRunnerConflicts);
            flags.add(id);
         }

         if (this.postChangeFlags != null) {
            flags.addAll(List.of(this.postChangeFlags));
         }

         if (!flags.isEmpty()) {
            option.setFlags(flags.toArray(Identifier[]::new));
         }

         option.setBinding(this.setter, this.getter);
         if (this.enabler != null) {
            Predicate<ConfigState> pred = this.enabler.tester;
            option.setEnabledProvider(s -> pred.test(s), this.enabler.dependencies);
         }

         option.setStorageHandler(ctx.saveHandler);
         option.setDefaultValue(this.getter.get());
         if (this.tooltipSupplier != null) {
            option.setTooltip(this.tooltipSupplier);
         }

         if (this.impact != null) {
            option.setImpact(this.impact);
         }

         return option;
      }
   }

   public static class Page extends SodiumConfigBuilder.Enableable<SodiumConfigBuilder.Page> {
      protected Component name;
      protected SodiumConfigBuilder.Group[] groups;

      public Page(Component name, SodiumConfigBuilder.Group... groups) {
         this.name = name;
         this.groups = groups;
      }

      protected OptionPageBuilder create(ConfigBuilder builder, SodiumConfigBuilder.BuildCtx ctx) {
         OptionPageBuilder page = builder.createOptionPage();
         page.setName(this.name);

         for (SodiumConfigBuilder.Group group : this.groups) {
            page.addOptionGroup(group.create(builder, ctx));
         }

         return page;
      }

      @Override
      protected SodiumConfigBuilder.Enableable[] getEnablerChildren() {
         return this.groups;
      }
   }

   public static class PostApplyOps implements FlagHook {
      private Map<Identifier, SodiumConfigBuilder.PostApplyOps.Hook> hooks = new LinkedHashMap<>();

      public SodiumConfigBuilder.PostApplyOps register(String name, Runnable postRunner, String... conflicts) {
         return this.register(Identifier.parse(name), postRunner, SodiumConfigBuilder.mapIds(conflicts));
      }

      public SodiumConfigBuilder.PostApplyOps register(Identifier name, Runnable postRunner, Identifier... conflicts) {
         this.hooks.put(name, new SodiumConfigBuilder.PostApplyOps.Hook(name, postRunner, new LinkedHashSet<>(List.of(conflicts))));
         return this;
      }

      protected SodiumConfigBuilder.PostApplyOps build() {
         boolean changed = false;

         do {
            changed = false;

            for (SodiumConfigBuilder.PostApplyOps.Hook hook : this.hooks.values()) {
               for (Identifier ref : new LinkedHashSet<>(hook.conflicts)) {
                  SodiumConfigBuilder.PostApplyOps.Hook other = this.hooks.getOrDefault(ref, null);
                  if (other != null) {
                     changed |= hook.conflicts.addAll(other.conflicts);
                  }
               }
            }
         } while (changed);

         return this;
      }

      public Collection<Identifier> getTriggers() {
         return this.hooks.keySet();
      }

      public void accept(Collection<Identifier> identifiers, ConfigState configState) {
         for (Identifier id : identifiers) {
            SodiumConfigBuilder.PostApplyOps.Hook hook = this.hooks.get(id);
            if (hook != null && Collections.disjoint(identifiers, hook.conflicts)) {
               hook.runnable.run();
            }
         }
      }

      private record Hook(Identifier name, Runnable runnable, Set<Identifier> conflicts) {
      }
   }
}
