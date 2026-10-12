package com.easyshopping.user.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.user.domain.UserAddress;
import com.easyshopping.user.dto.AddressDtos.AddressRequest;
import com.easyshopping.user.dto.AddressDtos.AddressResponse;
import com.easyshopping.user.repo.UserAddressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址（plans/ios-backend.md 5.2.2 / plans/android-backend.md 5.2.2）。
 *
 * <p>约束：每用户至多一个默认地址（设默认先清其它）；删除为软删，
 * 删除的是默认地址时自动把最早一条剩余地址提为默认。</p>
 */
@Service
public class AddressService {

    private final UserAddressRepository addressRepo;

    public AddressService(UserAddressRepository addressRepo) {
        this.addressRepo = addressRepo;
    }

    public List<AddressResponse> list(Long userId) {
        return addressRepo.findByUserIdAndDeletedFalseOrderByDefaultValueDescCreatedAtDesc(userId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public Long create(Long userId, AddressRequest request) {
        boolean isFirst = addressRepo.countByUserIdAndDeletedFalse(userId) == 0;
        boolean wantDefault = Boolean.TRUE.equals(request.isDefault()) || isFirst;
        if (wantDefault) {
            clearDefault(userId);
        }
        UserAddress address = new UserAddress(userId, request.name(), request.mobile(),
                request.province(), request.city(), request.district(), request.detail(),
                request.zipCode(), wantDefault, request.latitude(), request.longitude());
        return addressRepo.save(address).getId();
    }

    @Transactional
    public boolean update(Long userId, Long addressId, AddressRequest request) {
        UserAddress address = requireOwned(userId, addressId);
        if (Boolean.TRUE.equals(request.isDefault()) && !address.isDefault()) {
            clearDefault(userId);
            address.markDefault(true);
        }
        address.updateFrom(request.name(), request.mobile(), request.province(), request.city(),
                request.district(), request.detail(), request.zipCode(),
                request.latitude(), request.longitude());
        addressRepo.save(address);
        return true;
    }

    @Transactional
    public boolean setDefault(Long userId, Long addressId) {
        UserAddress address = requireOwned(userId, addressId);
        clearDefault(userId);
        address.markDefault(true);
        addressRepo.save(address);
        return true;
    }

    /** 软删；若删的是默认地址则把最早一条剩余地址提为默认（保证"默认地址唯一且尽量存在"）。 */
    @Transactional
    public boolean delete(Long userId, Long addressId) {
        UserAddress address = requireOwned(userId, addressId);
        boolean wasDefault = address.isDefault();
        address.softDelete();
        addressRepo.save(address);
        if (wasDefault) {
            List<UserAddress> remaining = addressRepo
                    .findByUserIdAndDeletedFalseOrderByDefaultValueDescCreatedAtDesc(userId);
            if (!remaining.isEmpty()) {
                remaining.get(0).markDefault(true);
                addressRepo.save(remaining.get(0));
            }
        }
        return true;
    }

    private void clearDefault(Long userId) {
        List<UserAddress> defaults = addressRepo.findByUserIdAndDefaultValueTrueAndDeletedFalse(userId);
        for (UserAddress d : defaults) {
            d.markDefault(false);
        }
        addressRepo.saveAll(defaults);
    }

    private UserAddress requireOwned(Long userId, Long addressId) {
        return addressRepo.findByIdAndUserIdAndDeletedFalse(addressId, userId)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "地址不存在"));
    }

    private AddressResponse toResponse(UserAddress a) {
        return new AddressResponse(a.getId(), a.getName(), MaskUtil.maskMobile(a.getMobile()),
                a.getProvince(), a.getCity(), a.getDistrict(), a.getDetail(), a.getZipCode(), a.isDefault());
    }
}
