package com.traum.metrics.interceptors;

import com.traum.microprofile.metrics.annotation.AsyncTimed;
import io.smallrye.metrics.TagsUtils;
import io.smallrye.metrics.elementdesc.AnnotationInfo;
import io.smallrye.metrics.elementdesc.MemberInfo;
import io.smallrye.metrics.elementdesc.adapter.MemberInfoAdapter;
import io.smallrye.metrics.elementdesc.adapter.cdi.CDIMemberInfoAdapter;
import io.smallrye.metrics.interceptors.MetricResolver.Of;
import io.smallrye.mutiny.Uni;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.SimpleTimer;
import org.eclipse.microprofile.metrics.SimpleTimer.Context;
import org.eclipse.microprofile.metrics.Tag;

@Dependent
@AsyncTimed
@Interceptor
public class AsyncTimedInterceptor {

  private final MetricRegistry registry;

  @Inject
  AsyncTimedInterceptor(MetricRegistry registry) {
    this.registry = registry;
  }

  @AroundInvoke
  Object meteredMethod(InvocationContext context) throws Exception {
    return this.timedCallable(context, context.getMethod());
  }

  private Map<String, MetricID> cache = new HashMap<>();

  private <E extends Member & AnnotatedElement> Object timedCallable(
      InvocationContext context, E element) throws Exception {

    final MetricID metricID =
        cache.computeIfAbsent(
            element.getName(),
            key -> {
              Of<AsyncTimed> resolvedAnnotation = AsyncTimedOf.of(element);

              final Metadata metadata =
                  Metadata.builder()
                      .withType(MetricType.SIMPLE_TIMER)
                      .withName(resolvedAnnotation.metricName())
                      .reusable(resolvedAnnotation.metricAnnotation().reusable())
                      .withOptionalDisplayName(resolvedAnnotation.metricAnnotation().displayName())
                      .withOptionalDescription(resolvedAnnotation.metricAnnotation().description())
                      .withOptionalUnit(resolvedAnnotation.metricAnnotation().unit())
                      .build();

              registry.simpleTimer(metadata, resolvedAnnotation.tags());

              return new MetricID(metadata.getName(), resolvedAnnotation.tags());
            });

    final SimpleTimer timer = (SimpleTimer) registry.getMetrics().get(metricID);

    if (timer == null) {
      throw new IllegalStateException(
          "No simple timer with metricID ["
              + metricID
              + "] found in registry ["
              + this.registry
              + "]");
    } else {
      Context time = timer.time();
      try {
        if (context.getMethod().getReturnType().isAssignableFrom(CompletionStage.class)) {
          return ((CompletionStage) context.proceed())
              .whenComplete(
                  (result, error) -> {
                    time.stop();
                  });
        }
        if (context.getMethod().getReturnType().isAssignableFrom(Uni.class)) {
          return ((Uni) context.proceed())
              .onItem()
              .invoke(result -> time.stop())
              .onFailure()
              .invoke(error -> time.stop());
        }
        throw new IllegalArgumentException(
            "Unsupported return type "
                + context.getMethod().getReturnType()
                + " from "
                + context.getMethod());
      } catch (Throwable t) {
        time.stop();
        throw t;
      }
    }
  }
}

class AsyncTimedOf implements Of<AsyncTimed> {

  private static final MemberInfoAdapter<Member> MEMBER_INFO_ADAPTER = new CDIMemberInfoAdapter();

  private final AnnotationInfo annotationInfo;
  private final String metricName;
  private final Tag[] tags;

  private AsyncTimedOf(AnnotationInfo annotationInfo, String metricName, Tag[] tags) {
    this.annotationInfo = annotationInfo;
    this.metricName = metricName;
    this.tags = tags;
  }

  static <E extends Member & AnnotatedElement> AsyncTimedOf of(E element) {
    final AsyncTimed annotation = element.getAnnotation(AsyncTimed.class);
    final MemberInfo memberInfo = MEMBER_INFO_ADAPTER.convert(element);
    final String name = annotation.name().isEmpty() ? memberInfo.getName() : annotation.name();
    final String metricName =
        annotation.absolute()
            ? name
            : MetricRegistry.name(memberInfo.getDeclaringClassName(), name);
    final AnnotationInfo annotationInfo = new AsyncTimedAnnotationInfo(annotation);
    final Tag[] tags = TagsUtils.parseTagsAsArray(annotation.tags());

    return new AsyncTimedOf(annotationInfo, metricName, tags);
  }

  @Override
  public boolean isPresent() {
    return true;
  }

  @Override
  public String metricName() {
    return metricName;
  }

  @Override
  public Tag[] tags() {
    return tags;
  }

  @Override
  public AnnotationInfo metricAnnotation() {
    return annotationInfo;
  }
}

class AsyncTimedAnnotationInfo implements AnnotationInfo {

  private final AsyncTimed instance;

  public <T extends Annotation> AsyncTimedAnnotationInfo(AsyncTimed instance) {
    this.instance = instance;
  }

  @Override
  public String name() {
    return instance.name();
  }

  @Override
  public String[] tags() {
    return instance.tags();
  }

  @Override
  public boolean absolute() {
    return instance.absolute();
  }

  @Override
  public String displayName() {
    return instance.displayName();
  }

  @Override
  public String description() {
    return instance.description();
  }

  @Override
  public String unit() {
    return instance.unit();
  }

  @Override
  public boolean reusable() {
    return instance.reusable();
  }

  @Override
  public String annotationName() {
    return instance.annotationType().getName();
  }
}