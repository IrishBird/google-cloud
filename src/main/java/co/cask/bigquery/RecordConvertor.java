/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.bigquery;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class RecordConvertor implements Serializable {

  public StructuredRecord toSr(JsonObject row, Schema schema) throws RecordConvertorException {
    StructuredRecord.Builder builder = StructuredRecord.builder(schema);
    List<Schema.Field> fields = schema.getFields();
    for (Schema.Field field : fields) {
      String name = field.getName();
      Object value = row.get(name);
      builder.set(name, decode(name, value, field.getSchema()));
    }
    return builder.build();
  }

  private Object decode(String name, Object object, Schema schema) throws RecordConvertorException {
    // Extract the type of the field.
    Schema.Type type = schema.getType();

    // Now based on the type, do the necessary decoding.
    switch (type) {
      case NULL:
      case BOOLEAN:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case BYTES:
      case STRING:
        return decodeSimpleTypes(name, object, schema);
      case ENUM:
        break;
      case ARRAY:
        return decodeArray(name, object, schema.getComponentSchema());
      case RECORD:
        return decodeRecord(name, object, schema);
      case MAP:
        Schema key = schema.getMapSchema().getKey();
        Schema value = schema.getMapSchema().getValue();
        // Should be fine to cast since schema tells us what it is.
        // noinspection unchecked
        return decodeMap(name, (Map<Object, Object>) object, key, value);
      case UNION:
        return decodeUnion(name, object, schema.getUnionSchemas());
    }

    throw new RecordConvertorException(
      String.format("Unable decode object '%s' with schema type '%s'.", name, type.toString())
    );
  }

  private StructuredRecord decodeRecord(String name, Object object, Schema schema) throws RecordConvertorException {
    if (object instanceof Map) {
      return decodeRecord(name, (Map) object, schema);
    } else if (object instanceof JsonObject) {
      return decodeRecord(name, (JsonObject) object, schema);
    } else if (object instanceof JsonArray) {
      List<Object> values = decodeArray(name, object, schema.getComponentSchema());
      StructuredRecord.Builder builder = StructuredRecord.builder(schema);
      builder.set(name, values);
      return builder.build();
    }
    throw new RecordConvertorException(
      String.format("Unable decode object '%s' with schema type '%s'.", name, schema.getType().toString())
    );
  }

  private StructuredRecord decodeRecord(String name,
                                        JsonObject nativeObject, Schema schema) throws RecordConvertorException {
    StructuredRecord.Builder builder = StructuredRecord.builder(schema);
    for (Schema.Field field : schema.getFields()) {
      String fieldName = field.getName();
      Object fieldVal = nativeObject.get(fieldName);
      builder.set(fieldName, decode(name, fieldVal, field.getSchema()));
    }
    return builder.build();
  }

  private StructuredRecord decodeRecord(String name, Map nativeObject, Schema schema) throws RecordConvertorException {
    StructuredRecord.Builder builder = StructuredRecord.builder(schema);
    for (Schema.Field field : schema.getFields()) {
      String fieldName = field.getName();
      Object fieldVal = nativeObject.get(fieldName);
      builder.set(fieldName, decode(name, fieldVal, field.getSchema()));
    }
    return builder.build();
  }

  @SuppressWarnings("RedundantCast")
  private Object decodeSimpleTypes(String name, Object object, Schema schema) throws RecordConvertorException {
    Schema.Type type = schema.getType();

    if (object == null || JsonNull.INSTANCE.equals(object)) {
      return null;
    } else if (object instanceof JsonPrimitive) {
      return getValue((JsonPrimitive) object);
    }

    switch (type) {
      case NULL:
        return null; // nothing much to do here.
      case INT:
        if (object instanceof Integer || object instanceof Short) {
          return (Integer) object;
        } else if (object instanceof String) {
          String value = (String) object;
          try {
            return Integer.parseInt(value);
          } catch (NumberFormatException e) {
            throw new RecordConvertorException(
              String.format("Unable to convert '%s' to integer for field name '%s'", value, name)
            );
          }
        } else {
          throw new RecordConvertorException(
            String.format("Schema specifies field '%s' is integer, but the value is not a integer or string. " +
                            "It is of type '%s'", name, object.getClass().getName())
          );
        }
      case LONG:
        if (object instanceof Long) {
          return (Long) object;
        } else if (object instanceof Integer) {
          return ((Integer) object).longValue();
        } else if (object instanceof Date) {
          return ((Date) object).getTime() / 1000; // Converts from milli-seconds to seconds.
        } else if (object instanceof java.sql.Date) {
          return ((java.sql.Date) object).getTime() / 1000;
        } else if (object instanceof Time) {
          return ((Time) object).getTime() / 1000;
        } else if (object instanceof Timestamp) {
          return ((Timestamp) object).getTime() / 1000;
        } else if (object instanceof Short) {
          return ((Short) object).longValue();
        } else if (object instanceof String) {
          String value = (String) object;
          try {
            return Long.parseLong(value);
          } catch (NumberFormatException e) {
            throw new RecordConvertorException(
              String.format("Unable to convert '%s' to long for field name '%s'", value, name)
            );
          }
        } else {
          throw new RecordConvertorException(
            String.format("Schema specifies field '%s' is long, but the value is nor a string or long. " +
                            "It is of type '%s'", name, object.getClass().getName())
          );
        }
      case FLOAT:
        if (object instanceof Float) {
          return (Float) object;
        } else if (object instanceof Long) {
          return ((Long) object).floatValue();
        } else if (object instanceof Integer) {
          return ((Integer) object).floatValue();
        } else if (object instanceof Short) {
          return ((Short) object).floatValue();
        } else if (object instanceof String) {
          String value = (String) object;
          try {
            return Float.parseFloat(value);
          } catch (NumberFormatException e) {
            throw new RecordConvertorException(
              String.format("Unable to convert '%s' to float for field name '%s'", value, name)
            );
          }
        } else {
          throw new RecordConvertorException(
            String.format("Schema specifies field '%s' is float, but the value is nor a string or float. " +
                            "It is of type '%s'", name, object.getClass().getName())
          );
        }
      case DOUBLE:
        if (object instanceof Double) {
          return (Double) object;
        } else if (object instanceof BigDecimal) {
          return ((BigDecimal) object).doubleValue();
        } else if (object instanceof Float) {
          return ((Float) object).doubleValue();
        } else if (object instanceof Long) {
          return ((Long) object).doubleValue();
        } else if (object instanceof Integer) {
          return ((Integer) object).doubleValue();
        } else if (object instanceof Short) {
          return ((Short) object).doubleValue();
        } else if (object instanceof String) {
          String value = (String) object;
          try {
            return Double.parseDouble(value);
          } catch (NumberFormatException e) {
            throw new RecordConvertorException(
              String.format("Unable to convert '%s' to double for field name '%s'", value, name)
            );
          }
        } else {
          throw new RecordConvertorException(
            String.format("Schema specifies field '%s' is double, but the value is nor a string or double. " +
                            "It is of type '%s'", name, object.getClass().getName())
          );
        }
      case BOOLEAN:
        if (object instanceof Boolean) {
          return (Boolean) object;
        } else if (object instanceof String) {
          String value = (String) object;
          try {
            return Boolean.parseBoolean(value);
          } catch (NumberFormatException e) {
            throw new RecordConvertorException(
              String.format("Unable to convert '%s' to boolean for field name '%s'", value, name)
            );
          }
        } else {
          throw new RecordConvertorException(
            String.format("Schema specifies field '%s' is double, but the value is nor a string or boolean. " +
                            "It is of type '%s'", name, object.getClass().getName())
          );
        }

      case STRING:
        return object.toString();

      case BYTES:
        if (object instanceof byte[]) {
          return (byte[]) object;
        } else if (object instanceof Boolean) {
          return Bytes.toBytes((Boolean) object);
        } else if (object instanceof Double) {
          return Bytes.toBytes((Double) object);
        } else if (object instanceof Float) {
          return Bytes.toBytes((Float) object);
        } else if (object instanceof Long) {
          return Bytes.toBytes((Long) object);
        } else if (object instanceof Integer) {
          return Bytes.toBytes((Integer) object);
        } else if (object instanceof Short) {
          return Bytes.toBytes((Short) object);
        } else if (object instanceof String) {
          return Bytes.toBytes((String) object);
        } else if (object instanceof BigDecimal) {
          return Bytes.toBytes((BigDecimal) object);
        } else {
          throw new RecordConvertorException(
            String.format("Unable to convert '%s' to bytes for field name '%s'", object.toString(), name)
          );
        }
    }
    throw new RecordConvertorException(
      String.format("Unable decode object '%s' with schema type '%s'.", name, type.toString())
    );
  }

  private Map<Object, Object> decodeMap(String name,
                                        Map<Object, Object> object, Schema key, Schema value)
    throws RecordConvertorException {
    Map<Object, Object> output = Maps.newHashMap();
    for (Map.Entry<Object, Object> entry : object.entrySet()) {
      output.put(decode(name, entry.getKey(), key), decode(name, entry.getValue(), value));
    }
    return output;
  }

  private Object decodeUnion(String name, Object object, List<Schema> schemas) throws RecordConvertorException {
    for (Schema schema : schemas) {
      return decode(name, object, schema);
    }
    throw new RecordConvertorException(
      String.format("Unable decode object '%s'.", name)
    );
  }

  private List<Object> decodeArray(String name, Object object, Schema schema) throws RecordConvertorException {
    if (object instanceof List) {
      return decodeArray(name, (List) object, schema);
    } else if (object instanceof JsonArray) {
      return decodeArray(name, (JsonArray) object, schema);
    }
    throw new RecordConvertorException(
      String.format("Unable to decode array '%s'", name)
    );
  }

  private List<Object> decodeArray(String name, JsonArray list, Schema schema) throws RecordConvertorException {
    List<Object> array = Lists.newArrayListWithCapacity(list.size());
    for (int i = 0; i < list.size(); ++i) {
      array.add(decode(name, list.get(i), schema));
    }
    return array;
  }

  private List<Object> decodeArray(String name, List list, Schema schema) throws RecordConvertorException {
    List<Object> array = Lists.newArrayListWithCapacity(list.size());
    for (Object object : array) {
      array.add(decode(name, object, schema));
    }
    return array;
  }

  /**
   * Extracts a value from the {@link JsonPrimitive}.
   *
   * @param primitive object to extract real value.
   * @return java type extracted from the {@link JsonPrimitive}
   */
  public static Object getValue(JsonPrimitive primitive) {
    if (primitive.isBoolean()) {
      return primitive.getAsBoolean();
    } else if (primitive.isNumber()) {
      Number number = primitive.getAsNumber();
      if (number instanceof Long) {
        return number.longValue();
      } else if (number instanceof Double) {
        return number.doubleValue();
      } else if (number instanceof Integer) {
        return number.intValue();
      } else if (number instanceof Short) {
        return number.shortValue();
      } else if (number instanceof Float) {
        return number.floatValue();
      } else if (number instanceof BigInteger) {
        return primitive.getAsBigInteger().longValue();
      } else if (number instanceof BigDecimal) {
        return primitive.getAsBigDecimal().doubleValue();
      } else if (number instanceof LazilyParsedNumber) {
        return primitive.getAsBigDecimal().doubleValue();
      }
    } else if (primitive.isString()) {
      return primitive.getAsString();
    } else if (primitive.isJsonNull()) {
      return primitive.getAsJsonNull();
    }
    return null;
  }
}