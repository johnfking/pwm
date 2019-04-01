/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2018 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet.changepw;

import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import password.pwm.PwmApplication;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.LoginInfoBean;
import password.pwm.bean.PasswordStatus;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.RequireCurrentPasswordMode;
import password.pwm.config.value.data.FormConfiguration;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmDataValidationException;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmSession;
import password.pwm.http.bean.ChangePasswordBean;
import password.pwm.http.servlet.forgottenpw.ForgottenPasswordUtil;
import password.pwm.ldap.PasswordChangeProgressChecker;
import password.pwm.ldap.UserInfo;
import password.pwm.ldap.auth.AuthenticationType;
import password.pwm.svc.event.AuditEvent;
import password.pwm.util.PasswordData;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.operations.PasswordUtility;

import java.util.Locale;
import java.util.Map;

public class ChangePasswordServletUtil
{

    private static final PwmLogger LOGGER = PwmLogger.forClass( ChangePasswordServletUtil.class );

    static boolean determineIfCurrentPasswordRequired(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession
    )
            throws PwmUnrecoverableException
    {
        final RequireCurrentPasswordMode currentSetting = pwmApplication.getConfig().readSettingAsEnum( PwmSetting.PASSWORD_REQUIRE_CURRENT, RequireCurrentPasswordMode.class );

        if ( currentSetting == RequireCurrentPasswordMode.FALSE )
        {
            return false;
        }

        if ( pwmSession.getLoginInfoBean().getType() == AuthenticationType.AUTH_FROM_PUBLIC_MODULE )
        {
            LOGGER.debug( pwmSession, () -> "skipping user current password requirement, authentication type is " + AuthenticationType.AUTH_FROM_PUBLIC_MODULE );
            return false;
        }

        {
            final PasswordData currentPassword = pwmSession.getLoginInfoBean().getUserCurrentPassword();
            if ( currentPassword == null )
            {
                LOGGER.debug( pwmSession, () -> "skipping user current password requirement, current password is not known to application" );
                return false;
            }
        }

        if ( currentSetting == RequireCurrentPasswordMode.TRUE )
        {
            return true;
        }

        final PasswordStatus passwordStatus = pwmSession.getUserInfo().getPasswordStatus();
        return currentSetting == RequireCurrentPasswordMode.NOTEXPIRED
                && !passwordStatus.isExpired()
                && !passwordStatus.isPreExpired()
                && !passwordStatus.isViolatesPolicy()
                && !pwmSession.getUserInfo().isRequiresNewPassword();

    }

    static void validateParamsAgainstLDAP(
            final Map<FormConfiguration, String> formValues,
            final PwmSession pwmSession,
            final ChaiUser theUser
    )
            throws ChaiUnavailableException, PwmDataValidationException
    {
        for ( final Map.Entry<FormConfiguration, String> entry : formValues.entrySet() )
        {
            final FormConfiguration formItem = entry.getKey();
            final String attrName = formItem.getName();
            final String value = entry.getValue();
            try
            {
                if ( !theUser.compareStringAttribute( attrName, value ) )
                {
                    final String errorMsg = "incorrect value for '" + attrName + "'";
                    final ErrorInformation errorInfo = new ErrorInformation( PwmError.ERROR_INCORRECT_RESPONSE, errorMsg, new String[]
                            {
                                    attrName,
                            }
                    );
                    LOGGER.debug( pwmSession, errorInfo );
                    throw new PwmDataValidationException( errorInfo );
                }
                LOGGER.trace( pwmSession, () -> "successful validation of ldap value for '" + attrName + "'" );
            }
            catch ( ChaiOperationException e )
            {
                LOGGER.error( pwmSession, "error during param validation of '" + attrName + "', error: " + e.getMessage() );
                throw new PwmDataValidationException( new ErrorInformation(
                        PwmError.ERROR_INCORRECT_RESPONSE,
                        "ldap error testing value for '" + attrName + "'",
                        new String[]
                                {
                                        attrName,
                                }
                ) );
            }
        }
    }

    static void sendChangePasswordEmailNotice(
            final PwmSession pwmSession,
            final PwmApplication pwmApplication
    )
            throws PwmUnrecoverableException
    {
        final Configuration config = pwmApplication.getConfig();
        final Locale locale = pwmSession.getSessionStateBean().getLocale();
        final EmailItemBean configuredEmailSetting = config.readSettingAsEmail( PwmSetting.EMAIL_CHANGEPASSWORD, locale );

        if ( configuredEmailSetting == null )
        {
            LOGGER.debug( pwmSession, () -> "skipping change password email for '" + pwmSession.getUserInfo().getUserIdentity() + "' no email configured" );
            return;
        }

        pwmApplication.getEmailQueue().submitEmail(
                configuredEmailSetting,
                pwmSession.getUserInfo(),

                pwmSession.getSessionManager().getMacroMachine( pwmApplication ) );
    }

    static void checkMinimumLifetime(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final ChangePasswordBean changePasswordBean,
            final UserInfo userInfo
    )
            throws PwmUnrecoverableException
    {
        if ( changePasswordBean.isNextAllowedTimePassed() )
        {
            return;
        }

        if ( userInfo.isWithinPasswordMinimumLifetime() )
        {
            boolean allowChange = false;
            if ( pwmSession.getLoginInfoBean().getAuthFlags().contains( AuthenticationType.AUTH_FROM_PUBLIC_MODULE ) )
            {
                allowChange = ForgottenPasswordUtil.permitPwChangeDuringMinLifetime(
                        pwmApplication,
                        pwmSession.getLabel(),
                        userInfo.getUserIdentity()
                );

            }

            if ( allowChange )
            {
                LOGGER.debug( pwmSession, () -> "current password is too young, but skipping enforcement of minimum lifetime check due to setting "
                        + PwmSetting.RECOVERY_MINIMUM_PASSWORD_LIFETIME_OPTIONS.toMenuLocationDebug( null, pwmSession.getSessionStateBean().getLocale() ) );
            }
            else
            {
                PasswordUtility.throwPasswordTooSoonException( userInfo, pwmSession.getLabel() );
            }
        }

        changePasswordBean.setNextAllowedTimePassed( true );
    }

    static void executeChangePassword(
            final PwmRequest pwmRequest,
            final PasswordData newPassword
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, PwmOperationalException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();
        final PwmSession pwmSession = pwmRequest.getPwmSession();
        // password accepted, setup change password
        final ChangePasswordBean cpb = pwmApplication.getSessionStateService().getBean( pwmRequest, ChangePasswordBean.class );

        // change password
        PasswordUtility.setActorPassword( pwmSession, pwmApplication, newPassword );

        //init values for progress screen
        {
            final PasswordChangeProgressChecker.ProgressTracker tracker = new PasswordChangeProgressChecker.ProgressTracker();
            final PasswordChangeProgressChecker checker = new PasswordChangeProgressChecker(
                    pwmApplication,
                    pwmSession.getUserInfo().getUserIdentity(),
                    pwmSession.getLabel(),
                    pwmSession.getSessionStateBean().getLocale()
            );
            cpb.setChangeProgressTracker( tracker );
            cpb.setChangePasswordMaxCompletion( checker.maxCompletionTime( tracker ) );
        }

        // send user an email confirmation
        ChangePasswordServletUtil.sendChangePasswordEmailNotice( pwmSession, pwmApplication );

        // send audit event
        pwmApplication.getAuditManager().submit( AuditEvent.CHANGE_PASSWORD, pwmSession.getUserInfo(), pwmSession );
    }

    static boolean warnPageShouldBeShown(
            final PwmRequest pwmRequest,
            final ChangePasswordBean changePasswordBean
    )
            throws PwmUnrecoverableException
    {
        final PwmSession pwmSession = pwmRequest.getPwmSession();

        if ( !pwmSession.getUserInfo().getPasswordStatus().isWarnPeriod() )
        {
            return false;
        }

        if ( pwmRequest.getPwmSession().getLoginInfoBean().isLoginFlag( LoginInfoBean.LoginFlag.skipNewPw ) )
        {
            return false;
        }

        if ( changePasswordBean.isWarnPassed() )
        {
            return false;
        }

        if ( pwmRequest.getPwmSession().getLoginInfoBean().getAuthFlags().contains( AuthenticationType.AUTH_FROM_PUBLIC_MODULE ) )
        {
            return false;
        }

        if ( pwmRequest.getPwmSession().getLoginInfoBean().getType() == AuthenticationType.AUTH_FROM_PUBLIC_MODULE )
        {
            return false;
        }

        return true;
    }
}
