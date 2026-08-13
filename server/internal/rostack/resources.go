package rostack

import (
	"encoding/json"
	"reflect"
	"sort"
	"strings"
	"unicode"

	"connect/server/internal/model"
)

var (
	collectionType  = reflect.TypeOf(model.Collection{})
	rawMessageType  = reflect.TypeOf(json.RawMessage{})
	resourceCatalog = buildResourceCatalog()
)

type resourceDefinition struct {
	name        string
	description string
	fieldPath   []int
	valueType   reflect.Type
}

type resourceItem struct {
	DeviceID    string `json:"deviceId"`
	DeviceName  string `json:"deviceName"`
	CollectedAt int64  `json:"collectedAt"`
	ReceivedAt  int64  `json:"receivedAt"`
	Value       any    `json:"value"`
}

func buildResourceCatalog() map[string]resourceDefinition {
	definitions := make(map[string]resourceDefinition)
	for index := 4; index < collectionType.NumField(); index++ {
		field := collectionType.Field(index)
		jsonName := jsonFieldName(field)
		walkResourceFields(definitions, []string{jsonName}, []int{index}, field.Type)
	}
	return definitions
}

func walkResourceFields(definitions map[string]resourceDefinition, names []string, path []int, valueType reflect.Type) {
	dereferenced := valueType
	for dereferenced.Kind() == reflect.Pointer {
		dereferenced = dereferenced.Elem()
	}
	if dereferenced.Kind() == reflect.Struct && dereferenced != rawMessageType {
		for index := 0; index < dereferenced.NumField(); index++ {
			field := dereferenced.Field(index)
			walkResourceFields(
				definitions,
				append(append([]string(nil), names...), jsonFieldName(field)),
				append(append([]int(nil), path...), index),
				field.Type,
			)
		}
		return
	}
	name := strings.Join(mapNames(names, kebabCase), "-")
	definitions[name] = resourceDefinition{
		name: name, description: "Latest " + strings.Join(names, ".") + " value for each reporting device.",
		fieldPath: append([]int(nil), path...), valueType: valueType,
	}
}

func mapNames(values []string, transform func(string) string) []string {
	result := make([]string, len(values))
	for index, value := range values {
		result[index] = transform(value)
	}
	return result
}

func kebabCase(value string) string {
	var result strings.Builder
	for index, current := range value {
		if unicode.IsUpper(current) && index > 0 {
			result.WriteByte('-')
		}
		result.WriteRune(unicode.ToLower(current))
	}
	return result.String()
}

func jsonFieldName(field reflect.StructField) string {
	name := strings.Split(field.Tag.Get("json"), ",")[0]
	if name == "" {
		return field.Name
	}
	return name
}

func sortedResourceDefinitions() []resourceDefinition {
	definitions := make([]resourceDefinition, 0, len(resourceCatalog))
	for _, definition := range resourceCatalog {
		definitions = append(definitions, definition)
	}
	sort.Slice(definitions, func(i, j int) bool { return definitions[i].name < definitions[j].name })
	return definitions
}

func (definition resourceDefinition) item(collection model.Collection) (resourceItem, bool) {
	value := reflect.ValueOf(collection)
	for _, fieldIndex := range definition.fieldPath {
		for value.Kind() == reflect.Pointer {
			if value.IsNil() {
				return resourceItem{}, false
			}
			value = value.Elem()
		}
		value = value.Field(fieldIndex)
	}
	if emptyResourceValue(value) {
		return resourceItem{}, false
	}
	for value.Kind() == reflect.Pointer {
		value = value.Elem()
	}
	return resourceItem{
		DeviceID: collection.DeviceID, DeviceName: collection.DeviceName,
		CollectedAt: collection.CollectedAt, ReceivedAt: collection.ReceivedAt, Value: value.Interface(),
	}, true
}

func emptyResourceValue(value reflect.Value) bool {
	for value.Kind() == reflect.Pointer {
		if value.IsNil() {
			return true
		}
		value = value.Elem()
	}
	return (value.Kind() == reflect.Slice || value.Kind() == reflect.Map) && value.Len() == 0
}

func resourceItems(definition resourceDefinition, collections []model.Collection) []resourceItem {
	items := make([]resourceItem, 0, len(collections))
	for _, collection := range collections {
		if item, present := definition.item(collection); present {
			items = append(items, item)
		}
	}
	return items
}

func resourceSchema(definition resourceDefinition, id string) map[string]any {
	return map[string]any{
		"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": id,
		"title": definition.description, "type": "object",
		"required": []string{"deviceId", "deviceName", "collectedAt", "receivedAt", "value"},
		"properties": map[string]any{
			"deviceId": map[string]any{"type": "string"}, "deviceName": map[string]any{"type": "string"},
			"collectedAt": map[string]any{"type": "integer"}, "receivedAt": map[string]any{"type": "integer"},
			"value": schemaForType(definition.valueType),
		},
		"additionalProperties": false,
	}
}

func schemaForType(valueType reflect.Type) map[string]any {
	for valueType.Kind() == reflect.Pointer {
		valueType = valueType.Elem()
	}
	if valueType == rawMessageType {
		return map[string]any{}
	}
	switch valueType.Kind() {
	case reflect.Bool:
		return map[string]any{"type": "boolean"}
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return map[string]any{"type": "integer"}
	case reflect.Float32, reflect.Float64:
		return map[string]any{"type": "number"}
	case reflect.String:
		return map[string]any{"type": "string"}
	case reflect.Slice, reflect.Array:
		return map[string]any{"type": "array", "items": schemaForType(valueType.Elem())}
	case reflect.Map:
		return map[string]any{"type": "object", "patternProperties": map[string]any{"^.*$": schemaForType(valueType.Elem())}, "additionalProperties": false}
	case reflect.Struct:
		properties := make(map[string]any, valueType.NumField())
		required := make([]string, 0, valueType.NumField())
		for index := 0; index < valueType.NumField(); index++ {
			field := valueType.Field(index)
			name := jsonFieldName(field)
			properties[name] = schemaForType(field.Type)
			if !strings.Contains(field.Tag.Get("json"), "omitempty") {
				required = append(required, name)
			}
		}
		schema := map[string]any{"type": "object", "properties": properties, "additionalProperties": false}
		if len(required) > 0 {
			schema["required"] = required
		}
		return schema
	default:
		return map[string]any{}
	}
}
