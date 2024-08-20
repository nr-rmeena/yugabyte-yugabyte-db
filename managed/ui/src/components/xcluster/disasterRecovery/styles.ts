import { makeStyles } from '@material-ui/core';

export const useModalStyles = makeStyles((theme) => ({
  stepContainer: {
    '& ol': {
      paddingLeft: theme.spacing(2),
      listStylePosition: 'outside',
      '& li::marker': {
        fontWeight: 'bold'
      }
    }
  },
  instruction: {
    display: 'flex',
    alignItems: 'center',
    gap: theme.spacing(1),

    marginBottom: theme.spacing(4)
  },
  fieldLabel: {
    display: 'flex',
    gap: theme.spacing(1),
    alignItems: 'center',

    marginBottom: theme.spacing(1)
  },
  fieldHelpText: {
    marginTop: theme.spacing(1),

    color: theme.palette.ybacolors.textGray,
    fontSize: '12px'
  },
  infoIcon: {
    '&:hover': {
      cursor: 'pointer'
    }
  }
}));
