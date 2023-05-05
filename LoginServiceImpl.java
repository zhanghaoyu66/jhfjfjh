package cn.intellijin.mall.service.impl;

import cn.intellijin.mall.config.Record;
import cn.intellijin.mall.constant.*;
import cn.intellijin.mall.dto.param.CodeLoginParam;
import cn.intellijin.mall.dto.param.PasswordLoginParam;
import cn.intellijin.mall.dto.param.ResetPasswordParam;
import cn.intellijin.mall.exception.ApiException;
import cn.intellijin.mall.pojo.Manager;
import cn.intellijin.mall.service.LoginService;
import cn.intellijin.mall.service.ManagerService;
import cn.intellijin.mall.util.*;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.security.PrivateKey;
import java.util.Date;
import java.util.Map;

/**
 * @program: mall
 * @description:
 * @author: Mr.Tan
 * @create: 2022-10-10 15:55
 **/
@Service
public class LoginServiceImpl implements LoginService {

    private final PrivateKey privateKey = RSAUtil.getPrivateKey("MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAINzCPJI/hPrX/yd4pEPONfDupTV\n" +
            "DU+3sys5LPm67UaYgZPSxvGxzVljRhfssG9KUH0R06NWRV++A0z+yyYEpDD9xVGErljMR1QZDC++\n" +
            "9tiX3t2Jh9N+vnYNWh5F0qWh1Ke4qZIGk7iM46gR7BGo8GXL0dQdyWZAmjRNSKD0PM9vAgMBAAEC\n" +
            "gYAzIEDwi3dXJAs3Y+lFZlhDg3tEfAEralWjkB9wGkZDWPm9FxQN2Yv3ImeW0pZlEtBvdMmOE/Xz\n" +
            "oSIDhm5ZISEC5LhB+t8LR4mkFN4bfxTu8HKqoKtMHfXDz2EoPIPRulkLTJ9tgF8KEf9La/47oUnt\n" +
            "SeNWtWd+BNETim0Y7WbxUQJBANWHQkRxPTpQBMniFKRTgzEFd/tUDxSPe/Cmw2Xw28wuP2A4xlGF\n" +
            "Za8VoNcJmn+I0G7uo/xA9Xd3CWq6CB5qYDMCQQCdmFxPjeSwRF1dUswNb99M6/c6Yf9+/n2vn0ro\n" +
            "wnOKx9Wy3KNLrYDVZR+Pyv0FjhVGOUrCCmefjDcfXzhLoSfVAkEAmjh07kXzePhuXPmC+ySuLmvK\n" +
            "uqV9ttXjKG7p1ejed1w3veGDq0Fzrb8rSeTPx6kjEdweaITqRXyeOo1ea8lc7QJAMtUZOWPoVt7G\n" +
            "SrrRLKhgG3ylMwS3F6xYuBQmYmuOPz5z9Ixsc5WUT8CdbJEqCeepfwwty+b1Q6ZDhW/+RY7GvQJB\n" +
            "ALfrdzOegdiTNcuSZNPX6PqVUPeTBg67RG7ofEd+zs9OILTZU5ldUA583zBNxwCHBjwx3VlPOqzM\n" +
            "1CngQZZ1prw=");

    private static final Logger logger = LoggerFactory.getLogger(LoginServiceImpl.class);

    @Autowired
    private ManagerService managerService;

    @Autowired
    private HttpServletResponse httpServletResponse;

    public LoginServiceImpl() throws Exception {
    }

    @Override
    public boolean beforePasswordLogin(String rsa) {
        String data = RSAUtil.decryptString(privateKey, rsa);
        PasswordLoginParam param = JSON.parseObject(data, PasswordLoginParam.class);
        return passwordLogin(ValidUtil.validUsername(param.getUsername()), ValidUtil.validPassword(param.getPassword()));
    }

    @Override
    @Record
    public boolean passwordLogin(String username,String password) {
        Manager manager = managerService.getManagerByUsername(username);
        if (manager == null) {
            logger.info(this.getClass().getName() + "------" + ErrorMsgConstant.USER_NOT_EXIST);
            throw new ApiException(ErrorMsgConstant.USER_NOT_EXIST);
        }
        Byte isForbidden = manager.getIsForbidden();
        if (isForbidden.equals(ByteConstant.TRUE)) {
            logger.info(this.getClass().getName() + " ------ " + ErrorMsgConstant.FORBIDDEN);
            throw new ApiException(ErrorMsgConstant.FORBIDDEN);
        }
        String secret = Sha256Utils.sha256(password, manager.getSalt());
        if (secret.equals(manager.getPassword())) {
            String currentTimeMillis = String.valueOf(System.currentTimeMillis());
            JedisUtil.setObject(TokenConstant.PREFIX_SHIRO_REFRESH_TOKEN + manager.getUsername(), currentTimeMillis, TokenConstant.REFRESH_TOKEN_EXPIRE_TIME);
            String token = JwtUtil.sign(manager.getUsername(), currentTimeMillis);
            httpServletResponse.setHeader("Authorization", token);
            httpServletResponse.setHeader("Access-Control-Expose-Headers", "Authorization");
            return true;
        }
        return false;
    }

    @Override
    public boolean beforeCodeLogin(String rsa) {
        String data = RSAUtil.decryptString(privateKey, rsa);
        CodeLoginParam param = JSON.parseObject(data, CodeLoginParam.class);
        return codeLogin(ValidUtil.validPhone(param.getPhone()), ValidUtil.validCode(param.getCode()));
    }

    @Override
    public boolean codeLogin(String phone, String code) {
        String smsCode = (String) JedisUtil.getObject(SmsConstant.LOGIN_CODE + phone);
        if (StringUtil.isBlank(smsCode)) {
            logger.info(this.getClass().getName() + "------" + ErrorMsgConstant.VERIFICATION_CODE_EXPIRE);
            throw new ApiException(ErrorMsgConstant.VERIFICATION_CODE_EXPIRE);
        }
        if (!smsCode.equals(code)) {
            logger.info(this.getClass().getName() + "------" + ErrorMsgConstant.VERIFICATION_CODE_ERROR);
            throw new ApiException(ErrorMsgConstant.VERIFICATION_CODE_ERROR);
        }
        Manager manager = managerService.getManagerByPhone(phone);
        if (manager == null) {
            logger.info(this.getClass().getName() + "------" + ErrorMsgConstant.USER_NOT_EXIST);
            throw new ApiException(ErrorMsgConstant.USER_NOT_EXIST);
        }
        Byte isForbidden = manager.getIsForbidden();
        if (isForbidden.equals(ByteConstant.TRUE)) {
            logger.info(this.getClass().getName() + " ------ " + ErrorMsgConstant.FORBIDDEN);
            throw new ApiException(ErrorMsgConstant.FORBIDDEN);
        }
        try {
            String currentTimeMillis = String.valueOf(System.currentTimeMillis());
            JedisUtil.setObject(TokenConstant.PREFIX_SHIRO_REFRESH_TOKEN + manager.getUsername(), currentTimeMillis, TokenConstant.REFRESH_TOKEN_EXPIRE_TIME);
            String token = JwtUtil.sign(manager.getUsername(), currentTimeMillis);
            httpServletResponse.setHeader("Authorization", token);
            httpServletResponse.setHeader("Access-Control-Expose-Headers", "Authorization");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean beforeResetPassword(String rsa) {
        String data = RSAUtil.decryptString(privateKey, rsa);
        ResetPasswordParam param = JSON.parseObject(data, ResetPasswordParam.class);
        return resetPassword(ValidUtil.validPassword(param.getNewPassword()),
                            ValidUtil.validPhone(param.getPhone()),
                            ValidUtil.validCode(param.getCode()));
    }

    @Override
    public boolean resetPassword(String newPassword, String phone, String code) {
        // 验证，确保健壮性
        String smsCode = (String) JedisUtil.getObject(SmsConstant.RESET_CODE + phone);
        if (StringUtil.isBlank(smsCode)) {
            logger.info(this.getClass().getName() + "------" + ErrorMsgConstant.VERIFICATION_CODE_EXPIRE);
            throw new ApiException(ErrorMsgConstant.VERIFICATION_CODE_EXPIRE);
        }
        if (!smsCode.equals(code)) {
            logger.info(this.getClass().getName() + "------" + ErrorMsgConstant.VERIFICATION_CODE_ERROR);
            throw new ApiException(ErrorMsgConstant.VERIFICATION_CODE_ERROR);
        }
        Manager manager = managerService.getManagerByPhone(phone);
        if (manager == null) {
            logger.info(this.getClass().getName() + "------" + ErrorMsgConstant.USER_NOT_EXIST);
            throw new ApiException(ErrorMsgConstant.USER_NOT_EXIST);
        }
        // 构建新manager
        Map<String, String> encrypt = Sha256Utils.encrypt(newPassword);
        manager.setPassword(encrypt.get(EncryptConstant.ENCRYPT_SOURCE));
        manager.setSalt(encrypt.get(EncryptConstant.SALT));
        manager.setUpdateTime(new Date());
        manager.setUpdateUser(manager.getId());
        return managerService.updatePassword(manager);
    }
}
