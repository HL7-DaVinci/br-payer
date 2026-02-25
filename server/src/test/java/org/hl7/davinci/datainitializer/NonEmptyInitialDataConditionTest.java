package org.hl7.davinci.datainitializer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.ResourceLoader;

class NonEmptyInitialDataConditionTest {

  private final NonEmptyInitialDataCondition condition = new NonEmptyInitialDataCondition();

  @Test
  void matches_returnsFalseWhenInitialDataIsMissing() {
    MockEnvironment env = new MockEnvironment();
    boolean matches = condition.matches(new TestConditionContext(env), null);
    assertFalse(matches);
  }

  @Test
  void matches_returnsTrueWhenInitialDataContainsAtLeastOneEntry() {
    MockEnvironment env = new MockEnvironment().withProperty("initial-data[0]", "library");
    boolean matches = condition.matches(new TestConditionContext(env), null);
    assertTrue(matches);
  }

  private static class TestConditionContext implements ConditionContext {
    private final Environment environment;

    private TestConditionContext(Environment environment) {
      this.environment = environment;
    }

    @Override
    public BeanDefinitionRegistry getRegistry() {
      return null;
    }

    @Override
    public ConfigurableListableBeanFactory getBeanFactory() {
      return null;
    }

    @Override
    public Environment getEnvironment() {
      return environment;
    }

    @Override
    public ResourceLoader getResourceLoader() {
      return null;
    }

    @Override
    public ClassLoader getClassLoader() {
      return getClass().getClassLoader();
    }
  }
}
