package com.app.user_service.mapper;

import com.app.user_service.dto.AddressDTO;
import com.app.user_service.model.Address;
import org.springframework.stereotype.Component;

@Component
public class AddressMapper {

    public Address toEntity(AddressDTO dto) {
        if (dto == null) {
            return null;
        }

        Address address = new Address();
        updateEntity(dto, address);
        return address;
    }

    public AddressDTO toDto(Address address) {
        if (address == null) {
            return null;
        }

        return new AddressDTO(
                address.getStreet(),
                address.getCity(),
                address.getState(),
                address.getCountry(),
                address.getZipcode()
        );
    }

    public void updateEntity(AddressDTO dto, Address address) {
        address.setStreet(dto.street());
        address.setCity(dto.city());
        address.setState(dto.state());
        address.setCountry(dto.country());
        address.setZipcode(dto.zipcode());
    }
}
