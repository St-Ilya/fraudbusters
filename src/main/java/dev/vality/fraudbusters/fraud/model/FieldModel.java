package dev.vality.fraudbusters.fraud.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FieldModel {

    private String name;
    private Object value;

}
