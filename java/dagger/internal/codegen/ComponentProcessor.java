/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.ISOLATING;

import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XProcessingStep;
import androidx.room.compiler.processing.compat.XConverters;
import com.google.auto.common.BasicAnnotationProcessor;
import com.google.auto.common.BasicAnnotationProcessor.Step;
import com.google.auto.service.AutoService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.errorprone.annotations.CheckReturnValue;
import dagger.Binds;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.internal.codegen.base.ClearableCache;
import dagger.internal.codegen.base.SourceFileGenerationException;
import dagger.internal.codegen.base.SourceFileGenerator;
import dagger.internal.codegen.binding.InjectBindingRegistry;
import dagger.internal.codegen.binding.MembersInjectionBinding;
import dagger.internal.codegen.binding.ProvisionBinding;
import dagger.internal.codegen.bindinggraphvalidation.BindingGraphValidationModule;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.compileroption.ProcessingEnvironmentCompilerOptions;
import dagger.internal.codegen.componentgenerator.ComponentGeneratorModule;
import dagger.internal.codegen.validation.BindingMethodProcessingStep;
import dagger.internal.codegen.validation.BindingMethodValidatorsModule;
import dagger.internal.codegen.validation.BindsInstanceProcessingStep;
import dagger.internal.codegen.validation.ExternalBindingGraphPlugins;
import dagger.internal.codegen.validation.InjectBindingRegistryModule;
import dagger.internal.codegen.validation.MonitoringModuleProcessingStep;
import dagger.internal.codegen.validation.MultibindingAnnotationsProcessingStep;
import dagger.internal.codegen.validation.ValidationBindingGraphPlugins;
import dagger.multibindings.IntoSet;
import dagger.spi.BindingGraphPlugin;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor;

/**
 * The annotation processor responsible for generating the classes that drive the Dagger 2.0
 * implementation.
 *
 * <p>TODO(gak): give this some better documentation
 */
@IncrementalAnnotationProcessor(ISOLATING)
@AutoService(Processor.class)
public class ComponentProcessor extends BasicAnnotationProcessor {
  private final Optional<ImmutableSet<BindingGraphPlugin>> testingPlugins;

  @Inject InjectBindingRegistry injectBindingRegistry;
  @Inject SourceFileGenerator<ProvisionBinding> factoryGenerator;
  @Inject SourceFileGenerator<MembersInjectionBinding> membersInjectorGenerator;
  @Inject ImmutableList<Step> processingSteps;
  @Inject ValidationBindingGraphPlugins validationBindingGraphPlugins;
  @Inject ExternalBindingGraphPlugins externalBindingGraphPlugins;
  @Inject Set<ClearableCache> clearableCaches;

  public ComponentProcessor() {
    this.testingPlugins = Optional.empty();
  }

  private ComponentProcessor(Iterable<BindingGraphPlugin> testingPlugins) {
    this.testingPlugins = Optional.of(ImmutableSet.copyOf(testingPlugins));
  }

  /**
   * Creates a component processor that uses given {@link BindingGraphPlugin}s instead of loading
   * them from a {@link java.util.ServiceLoader}.
   */
  @VisibleForTesting
  public static ComponentProcessor forTesting(BindingGraphPlugin... testingPlugins) {
    return forTesting(Arrays.asList(testingPlugins));
  }

  /**
   * Creates a component processor that uses given {@link BindingGraphPlugin}s instead of loading
   * them from a {@link java.util.ServiceLoader}.
   */
  @VisibleForTesting
  public static ComponentProcessor forTesting(Iterable<BindingGraphPlugin> testingPlugins) {
    return new ComponentProcessor(testingPlugins);
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public ImmutableSet<String> getSupportedOptions() {
    return ImmutableSet.<String>builder()
        .addAll(ProcessingEnvironmentCompilerOptions.supportedOptions())
        .addAll(validationBindingGraphPlugins.allSupportedOptions())
        .addAll(externalBindingGraphPlugins.allSupportedOptions())
        .build();
  }

  @Override
  protected Iterable<? extends Step> steps() {
    ProcessorComponent.factory()
        .create(processingEnv, testingPlugins.orElseGet(this::loadExternalPlugins))
        .inject(this);

    validationBindingGraphPlugins.initializePlugins();
    externalBindingGraphPlugins.initializePlugins();

    return processingSteps;
  }

  private ImmutableSet<BindingGraphPlugin> loadExternalPlugins() {
    return ServiceLoaders.load(processingEnv, BindingGraphPlugin.class);
  }

  @Singleton
  @Component(
      modules = {
        BindingGraphValidationModule.class,
        BindingMethodValidatorsModule.class,
        ComponentGeneratorModule.class,
        InjectBindingRegistryModule.class,
        ProcessingEnvironmentModule.class,
        ProcessingRoundCacheModule.class,
        ProcessingStepsModule.class,
        SourceFileGeneratorsModule.class,
      })
  interface ProcessorComponent {
    void inject(ComponentProcessor processor);

    static Factory factory() {
      return DaggerComponentProcessor_ProcessorComponent.factory();
    }

    @Component.Factory
    interface Factory {
      @CheckReturnValue
      ProcessorComponent create(
          @BindsInstance ProcessingEnvironment processingEnv,
          @BindsInstance ImmutableSet<BindingGraphPlugin> externalPlugins);
    }
  }

  @Module
  interface ProcessingStepsModule {
    @Binds
    @IntoSet
    ClearableCache bindXProcessingEnvCache(XProcessingEnvCache cache);

    @Provides
    static ImmutableList<Step> processingSteps(
        XProcessingEnvCache xProcessingEnvCache,
        MapKeyProcessingStep mapKeyProcessingStep,
        InjectProcessingStep injectProcessingStep,
        AssistedInjectProcessingStep assistedInjectProcessingStep,
        AssistedFactoryProcessingStep assistedFactoryProcessingStep,
        AssistedProcessingStep assistedProcessingStep,
        MonitoringModuleProcessingStep monitoringModuleProcessingStep,
        MultibindingAnnotationsProcessingStep multibindingAnnotationsProcessingStep,
        BindsInstanceProcessingStep bindsInstanceProcessingStep,
        ModuleProcessingStep moduleProcessingStep,
        ComponentProcessingStep componentProcessingStep,
        ComponentHjarProcessingStep componentHjarProcessingStep,
        BindingMethodProcessingStep bindingMethodProcessingStep,
        CompilerOptions compilerOptions) {
      return Stream.of(
              mapKeyProcessingStep,
              injectProcessingStep,
              assistedInjectProcessingStep,
              assistedFactoryProcessingStep,
              assistedProcessingStep,
              monitoringModuleProcessingStep,
              multibindingAnnotationsProcessingStep,
              bindsInstanceProcessingStep,
              moduleProcessingStep,
              compilerOptions.headerCompilation()
                  ? componentHjarProcessingStep
                  : componentProcessingStep,
              bindingMethodProcessingStep)
          // TODO(bcorso): Remove DelegatingStep once we've migrated to XBasicAnnotationProcessor.
          .map(step -> DelegatingStep.create(xProcessingEnvCache, step))
          .collect(toImmutableList());
    }
  }

  @Override
  protected void postRound(RoundEnvironment roundEnv) {
    if (!roundEnv.processingOver()) {
      try {
        injectBindingRegistry.generateSourcesForRequiredBindings(
            factoryGenerator, membersInjectorGenerator);
      } catch (SourceFileGenerationException e) {
        e.printMessageTo(processingEnv.getMessager());
      }
    }
    clearableCaches.forEach(ClearableCache::clearCache);
  }

  /** An {@link XProcessingEnv} cache that clears tha cache on each processing round. */
  @Singleton
  static final class XProcessingEnvCache implements ClearableCache {
    private final ProcessingEnvironment processingEnv;
    private XProcessingEnv xProcessingEnv;

    @Inject
    XProcessingEnvCache(ProcessingEnvironment processingEnv) {
      this.processingEnv = processingEnv;
    }

    @Override
    public void clearCache() {
      xProcessingEnv = null;
    }

    public XProcessingEnv get() {
      if (xProcessingEnv == null) {
        xProcessingEnv = XProcessingEnv.create(processingEnv);
      }
      return xProcessingEnv;
    }
  }

  /** A {@link Step} that delegates to a {@link XProcessingStep}. */
  private static final class DelegatingStep implements Step {
    static Step create(XProcessingEnvCache xProcessingEnvCache, XProcessingStep xProcessingStep) {
      return new DelegatingStep(xProcessingEnvCache, xProcessingStep);
    }

    private final XProcessingEnvCache xProcessingEnvCache;
    private final XProcessingStep delegate;

    DelegatingStep(XProcessingEnvCache xProcessingEnvCache, XProcessingStep delegate) {
      this.xProcessingEnvCache = xProcessingEnvCache;
      this.delegate = delegate;
    }

    @Override
    public Set<String> annotations() {
      return delegate.annotations();
    }

    @Override
    public Set<? extends Element> process(
        ImmutableSetMultimap<String, Element> elementsByAnnotation) {
      XProcessingEnv xProcessingEnv = xProcessingEnvCache.get();
      return delegate.process(
              xProcessingEnv,
              Maps.transformValues(
                  elementsByAnnotation.asMap(),
                  javacElements ->
                      javacElements.stream()
                          .map(element -> XConverters.toXProcessing(element, xProcessingEnv))
                          .collect(toImmutableSet())))
          .stream()
          .map(XConverters::toJavac)
          .collect(toImmutableSet());
    }
  }
}
