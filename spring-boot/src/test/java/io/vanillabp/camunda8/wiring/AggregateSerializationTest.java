package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.api.JsonMapper;
import io.camunda.zeebe.client.impl.ZeebeObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test to ensure JSON serialization of aggregates applies to documentation
 * (see section &quot;Workflow aggregate serialization&quot;).
 */
public class AggregateSerializationTest {
    
    private JsonMapper jsonMapper;
    
    @BeforeEach
    public void buildJsonMapper() {
        
        jsonMapper = new ZeebeObjectMapper(new ObjectMapper());
        
    }
    
    @Test
    public void testSimpleBeanSerialization() {
        
        final var subBean = new SubBean();
        subBean.setSubProperty("X\nY\nZ");
        
        final var serializedSimpleBean = jsonMapper.toJson(subBean);
        final var deserializedSimpleBean = jsonMapper
                .fromJson(serializedSimpleBean, SubBean.class);
        
        assertNotNull(deserializedSimpleBean);
        assertEquals(subBean.getSubProperty(), deserializedSimpleBean.getSubProperty());
        
    }
    
    @Test
    public void testComplexBeanSerialization() {

        final var subBean = new SubBean();
        subBean.setSubProperty("X\nY\nZ");
        final var complexBean = new TestBeanWithGetters();
        complexBean.setSubProperty(subBean);
        complexBean.setFloatProperty(1.5f);
        complexBean.setBooleanProperty(true);
        
        final var serializedComplextBean = jsonMapper.toJson(complexBean);
        final var deserializedComplextBean = jsonMapper
                .fromJson(serializedComplextBean, TestBeanWithGetters.class);
        
        assertNotNull(deserializedComplextBean);
        assertEquals(complexBean.getFloatProperty(), deserializedComplextBean.getFloatProperty());
        assertEquals(complexBean.isBooleanProperty(), deserializedComplextBean.isBooleanProperty());
        assertNotNull(deserializedComplextBean.getSubProperty());
        assertEquals(complexBean.getSubProperty().getSubProperty(),
                deserializedComplextBean.getSubProperty().getSubProperty());

    }

    @Test
    public void testSerializationWithoutGetters() {
        
        final var testBean = new TestBeanWithoutGetters();
        testBean.stringProperty = "X\nY\nZ";
        
        final var serializedBean = jsonMapper.toJson(testBean);
        final var deserializedBean = jsonMapper
                .fromJson(serializedBean, TestBeanWithoutGetters.class);
        
        assertNotNull(deserializedBean);
        assertEquals(testBean.stringProperty, deserializedBean.stringProperty);
        
    }

    @Test
    public void testSerializationOfBeanHavingCalculatedProperties() {
        
        final var testBean1 = new TestBeanCalculatedProperties();
        testBean1.setValue(0.5f);
        
        assertFalse(testBean1.isValid());
        
        final var serializedBean1HavingFalse = jsonMapper.toJson(testBean1);
        
        assertNotNull(serializedBean1HavingFalse);
        assertTrue(serializedBean1HavingFalse.contains("\"valid\":false"));
        
        final var testBean2 = new TestBeanCalculatedProperties();
        testBean2.setValue(1.5f);
        
        assertTrue(testBean2.isValid());
        
        final var serializedBean2HavingTrue = jsonMapper.toJson(testBean2);
        
        assertNotNull(serializedBean2HavingTrue);
        assertTrue(serializedBean2HavingTrue.contains("\"valid\":true"));
        
    }

    @Test
    public void testSerializationOfBeanHavingCalculatedPropertiesButNoGetters() {
        
        final var testBean1 = new TestBeanWithoutGettersAndCalculatedProperties();
        testBean1.value = 0.5f;
        
        assertFalse(testBean1.isValid());
        
        final var serializedBean1HavingFalse = jsonMapper.toJson(testBean1);
        
        assertNotNull(serializedBean1HavingFalse);
        assertTrue(serializedBean1HavingFalse.contains("\"valid\":false"));
        
        final var testBean2 = new TestBeanWithoutGettersAndCalculatedProperties();
        testBean2.value = 1.5f;
        
        assertTrue(testBean2.isValid());
        
        final var serializedBean2HavingTrue = jsonMapper.toJson(testBean2);
        
        assertNotNull(serializedBean2HavingTrue);
        assertTrue(serializedBean2HavingTrue.contains("\"valid\":true"));
        
    }

    @Test
    public void testSerializationOfTransientRelations() {
        
        final var subBean = new SubBean();
        subBean.setSubProperty("X\nY\nZ");

        final var testBean = new TestBeanWithTransientRelation();
        testBean.setStringProperty("yeah");
        testBean.setSubProperty(subBean);
        
        final var serializedBean = jsonMapper.toJson(testBean);
        
        assertNotNull(serializedBean);
        assertFalse(serializedBean.contains("\"subProperty\":{\"subProperty\":\"X\\nY\\nZ\"}"));
        
    }
    
    public static class SubBean {
        private String subProperty;

        public String getSubProperty() {
            return subProperty;
        }

        public void setSubProperty(String subProperty) {
            this.subProperty = subProperty;
        }
    }

    public static class TestBeanWithGetters {
        private boolean booleanProperty;
        private Float floatProperty;
        private SubBean subProperty;

        public Float getFloatProperty() {
            return floatProperty;
        }

        public void setFloatProperty(Float floatProperty) {
            this.floatProperty = floatProperty;
        }

        public boolean isBooleanProperty() {
            return booleanProperty;
        }

        public void setBooleanProperty(boolean booleanProperty) {
            this.booleanProperty = booleanProperty;
        }

        public SubBean getSubProperty() {
            return subProperty;
        }

        public void setSubProperty(SubBean subProperty) {
            this.subProperty = subProperty;
        }
    }
    
    public static class TestBeanWithoutGetters {
        public String stringProperty;
    }
    
    public static class TestBeanCalculatedProperties {
        private float value;
        public float getValue() {
            return value;
        }
        public void setValue(float value) {
            this.value = value;
        }
        public boolean isValid() {
            return this.value > 1;
        }
    }

    public static class TestBeanWithoutGettersAndCalculatedProperties {
        public float value;
        public boolean isValid() {
            return this.value > 1;
        }
    }
    
    public static class TestBeanWithTransientRelation {
        private String stringProperty;
        private SubBean subProperty;
        public String getStringProperty() {
            return stringProperty;
        }
        public void setStringProperty(String stringProperty) {
            this.stringProperty = stringProperty;
        }
        @JsonIgnore
        public SubBean getSubProperty() {
            return subProperty;
        }
        public void setSubProperty(SubBean subProperty) {
            this.subProperty = subProperty;
        }
    }
}
